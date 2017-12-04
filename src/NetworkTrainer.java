import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class NetworkTrainer {	
	/* 
	 * Hash maps used to store the mean firing rates of the neurons of each node. Each
	 * map corresponds to a different class of inputs.
	 */
	
	private static ConcurrentHashMap<Integer, float[]> undeterminedClassFiringRateMap;
	private static ConcurrentHashMap<Integer, float[]> trackClassFiringRateMap;
	private static ConcurrentHashMap<Integer, float[]> spotClassFiringRateMap;
	private static ConcurrentHashMap<Integer, float[]> untaggedFiringRateMap; // Stores for each node the firing rates in response to an input that must be classified. 
	
	// Number that keeps track of which kind of input is being used to stimulate the network.  
	private static volatile int currentInputClass = Constants.UNDETERMINED;
		
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	// Constants local to this class.
	private final byte UPDATE_WEIGHT = (byte)1;
	private final byte DONT_UPDATE_WEIGHT = (byte)0;
	private final static int NO_INPUT = -1;
	
	// Variables used to determine to which class the current input belongs.
	private static volatile int tentativeClass = -1; // TODO: Should these two be atomic?
	private static volatile float probability = 0.0f;
	
	static boolean shutdown = false;
			
	/**
	 * Class that waits for UDP packets to arrive at a specific port and
	 * update the firing rates of the neurons that produced the spikes.  
	 */
	
	private static class SpikesReceiver extends Thread {	
		
		boolean shutdown = false;
		boolean isTrainingSession = false;
		DatagramSocket socket;
		
		SpikesReceiver(boolean isTrainingSession) {
			this.isTrainingSession = isTrainingSession;
		}
		
		/**
		 * Inner class that implements Runnable and takes care of updating the 
		 * firing rates of the neurons that belong to the node that has sent 
		 * the spikesPacket.  
		 */
		
		private class WorkerThread implements Runnable {
			DatagramPacket spikesPacket;
			byte[] spikesBuffer;
			
			WorkerThread(DatagramPacket spikesPacket, byte[] spikesBuffer) {
				this.spikesPacket = spikesPacket;
				this.spikesBuffer = spikesBuffer;
			}
			
			@Override
			public void run() {
        		if (spikesPacket != null) {     			
        			int ipHashCode = spikesPacket.getAddress().hashCode();          			
        			int numOfNeurons = VirtualLayerManager.nodesTable.get(ipHashCode).terminal.numOfNeurons; 
        			
        			/*
        			 * If the network is being trained, compute the mean rate of the neurons for each 
        			 * input class and save it in the appropriate hash map. Otherwise, compare the rate with
        			 * that previously saved to determine to which class the current input belongs to. 
        			 */
        			
        			if (isTrainingSession) {
              			float[] meanFiringRates = null;
	        			
            			switch(currentInputClass) {
            			case Constants.UNDETERMINED: 
            				meanFiringRates = undeterminedClassFiringRateMap.get(ipHashCode);        				
            				break;
            			case Constants.TRACK:
            				meanFiringRates = trackClassFiringRateMap.get(ipHashCode);
            				break;
            			case Constants.SPOT:
            				meanFiringRates = spotClassFiringRateMap.get(ipHashCode);
            			}
            			
            			assert meanFiringRates != null;
            			
	        			// Iterating over the the neurons that produced the spike trains.
	        			for (int index = 0; index < numOfNeurons; index++) { 
	        				int byteIndex = index / 8;
	        				
	        				// If the current neuron had emitted a spike, increase the firing rate using a simple moving average algorithm. 
	        				meanFiringRates[index] = ((spikesBuffer[byteIndex] >> (index - byteIndex * 8)) & 1) == 1 ? 
	        						meanFiringRates[index] + Constants.MEAN_RATE_INCREMENT * (1 - meanFiringRates[index]) : 
	        							meanFiringRates[index] - Constants.MEAN_RATE_INCREMENT * meanFiringRates[index];
	        			}
        			} else {
        				// Vector of the firing rates that must be compared. 
        				float[] untaggedFiringRate = untaggedFiringRateMap.get(ipHashCode);
        				
        				/* Vectors storing the different classes of firing rates that untaggedFiringRate can belong to. */
        				
        				float[] undeterminedFiringRate = undeterminedClassFiringRateMap.get(ipHashCode); 
        				float[] trackFiringRate = trackClassFiringRateMap.get(ipHashCode);
        				float[] spotFiringRate = spotClassFiringRateMap.get(ipHashCode);
        				
        				/* Floats indicating how similar the firing rate is to that of a specific class. */
        				
        				float undeterminedClassDistance = 0.0f;
        				float trackClassDistance = 0.0f;
        				float spotClassDistance = 0.0f;
        				        				
        				for (int index = 0; index < numOfNeurons; index++) { 
	        				int byteIndex = index / 8;
	        				
	        				untaggedFiringRate[index] = ((spikesBuffer[byteIndex] >> (index - byteIndex * 8)) & 1) == 1 ? 
	        						untaggedFiringRate[index] + Constants.MEAN_RATE_INCREMENT * (1 - untaggedFiringRate[index]) : 
	        							untaggedFiringRate[index] - Constants.MEAN_RATE_INCREMENT * untaggedFiringRate[index];
	        						
	        				undeterminedClassDistance += Math.pow(undeterminedFiringRate[index] - untaggedFiringRate[index], 2);
	        				trackClassDistance += Math.pow(trackFiringRate[index] - untaggedFiringRate[index], 2);
	        				spotClassDistance += Math.pow(spotFiringRate[index] - untaggedFiringRate[index], 2);
	        			}
        				
        				// The distances are normalized.
        				undeterminedClassDistance = (float) (Math.sqrt(undeterminedClassDistance) / numOfNeurons);
        				trackClassDistance = (float) (Math.sqrt(trackClassDistance) / numOfNeurons);
        				spotClassDistance = (float) (Math.sqrt(trackClassDistance) / numOfNeurons);
        				
        				// The probability for each class is computed.
        				undeterminedClassDistance = 1 - undeterminedClassDistance;
        				trackClassDistance = 1 - trackClassDistance;
        				spotClassDistance = 1 - spotClassDistance;
        				
        				// The tentative class is determined and the overall probability is computed.
        				float norm = undeterminedClassDistance + trackClassDistance + spotClassDistance;
        				
        				if (undeterminedClassDistance >= trackClassDistance & 
        						undeterminedClassDistance >= spotClassDistance) {
        					tentativeClass = Constants.UNDETERMINED;
        					probability = undeterminedClassDistance / norm;
        				} else if (trackClassDistance >= undeterminedClassDistance & 
        						trackClassDistance >= spotClassDistance) {
        					tentativeClass = Constants.TRACK;
        					probability = trackClassDistance / norm;
        				} else {
        					tentativeClass = Constants.SPOT;
        					probability = spotClassDistance / norm;
        				}        				
        				
        			}

        		}
			}
		}
		
		@Override
		public void run() {
			super.run();
			
			ExecutorService workerThreadsExecutor = Executors.newCachedThreadPool(); // TODO: Have fixed pool size with only one thread per node. 			
									
	    	/* Create the datagram socket used to read the incoming spikes. */
	    	
	    	socket = null;
	    	try {
	    		socket = new DatagramSocket(Constants.APP_UDP_PORT);
	    		socket.setTrafficClass(Constants.IPTOS_THROUGHPUT);
	    	} catch (SocketException e) {
	    		e.printStackTrace();
	    	}
	    	assert socket != null;    	
	    	
	    	while (!shutdown) {
	    		// Receive the datagram packet with the latest spikes array.        		
        		DatagramPacket spikesPacket = null;
    			byte[] spikesBuffer = new byte[Constants.MAX_DATA_BYTES];
        		try {
        			spikesPacket = new DatagramPacket(spikesBuffer, Constants.MAX_DATA_BYTES);
        			socket.receive(spikesPacket);
        			spikesBuffer = spikesPacket.getData();
        		} catch (IOException e) {
        			System.out.println("spikesReceiver socket is closed");
        			break;
        		}
        		
        		// Create a worker thread to do any kind of post-processing on the spikes sent
        		// by the terminal.
        		if (currentInputClass != NO_INPUT) // Start a thread only if some input is currently being sent. 
        			workerThreadsExecutor.execute(new WorkerThread(spikesPacket, spikesBuffer));                		
	    	}
	    	
	    	/* Shutdown executor. */
	    	
	    	workerThreadsExecutor.shutdown();	    	
	    	try {
	    		boolean workerThreadsExecutorIsShutdown = workerThreadsExecutor.awaitTermination(1, TimeUnit.SECONDS);
	    		if (!workerThreadsExecutorIsShutdown) {
	    			System.out.println("ERROR: Failed to shutdown worker threads executor.");
	    		}
	    	} catch (InterruptedException e) {
	    		e.printStackTrace();
	    	}
	    	
	    	if (!socket.isClosed())
	    		socket.close();	    	
		}
		
	}
		
	/**
	 * The method checks if the chosen nodes satisfy the necessary conditions
	 * to build a network such as the specific one needed by this app, MuonDetectorTeacher. 
	 */
	
	boolean checkTopology() {
		for (Node excNode : Main.excNodes) {
			
			/*
			 * Check if the node has lateral connections. If it doesn't, and it 
			 * cannot support them, return with an error. 
			 */
			
			if (!excNode.hasLateralConnections()) 
				if (!excNode.changeLateralConnectionsOption()) {
					Main.updateLogPanel("Node " + excNode.terminal.ip + " can't support lateral conn.", Color.RED);
					return false;
				}
			
			/*
			 * Check if the node has inhibitory synapses and dendrites. 
			 */
			
			boolean hasInhDendrites = false;
			for (Node presynapticNode : excNode.presynapticNodes) {
				if (Main.inhNodes.contains(presynapticNode))
					hasInhDendrites = true;
			}
			if (!hasInhDendrites) {
				Main.updateLogPanel("Node " + excNode.terminal.ip + " doesn't have inh. dendrites.", Color.RED);
				return false;
			}
			
			boolean hasInhSynapses = false;
			for (Node postsynapticNode : excNode.postsynapticNodes) {
				if (Main.inhNodes.contains(postsynapticNode))
					hasInhSynapses = true;
			}
			if (!hasInhSynapses) {
				Main.updateLogPanel("Node " + excNode.terminal.ip + " doesn't have inh. synapses.", Color.RED);
				return false;
			}			
			
			/*
			 * Check if the terminal has enough dendrites to receive the sample pic 
			 * to be analyzed. 
			 */
			
			if (excNode.terminal.numOfDendrites < Constants.MAX_PIC_PIXELS) {
				Main.updateLogPanel("Node " + excNode.terminal.ip + " doesn't have enough dendrites for pic.", Color.RED);
				return false;
			}
			
			/*
			 * If the node satisfies all the requirements, make it 
			 * unavailable to the Overmind server. 
			 */
			
			VirtualLayerManager.availableNodes.remove(excNode);
		}	
		
		for (Node inhNode : Main.inhNodes) {
			
			/*
			 * Check if the node has excitatory synapses and dendrites.
			 */
			
			boolean hasExcDendrites = false;
			for (Node presynapticNode : inhNode.presynapticNodes) {
				if (Main.excNodes.contains(presynapticNode))
					hasExcDendrites = true;
			}
			if (!hasExcDendrites) {
				Main.updateLogPanel("Node " + inhNode.terminal.ip + " doesn't have exc. dendrites.", Color.RED);
				return false;
			}
			
			boolean hasExcSynapses = false;
			for (Node postsynapticNode : inhNode.postsynapticNodes) {
				if (Main.excNodes.contains(postsynapticNode))
					hasExcSynapses = true;
			}
			if (!hasExcSynapses) {
				Main.updateLogPanel("Node " + inhNode.terminal.ip + " doesn't have exc. synapses.", Color.RED);
				return false;
			}			
			
			VirtualLayerManager.availableNodes.remove(inhNode);
		}
				
		return true;
	}	
	
	/**
	 * After the topology of the network has been validated, this method set
	 * the synaptic weights of each node to their default values. Additionally, it determines 
	 * whether the node should be updated regularly by the client using the learning algorithm. 
	 */
	
	boolean setSynapticWeights() {	
		final boolean STREAM_INTERRUPTED = false;
		final boolean OPERATION_SUCCESSFUL = true;

		Random randomNumber = new Random();			
				
		/*
		 * If the hash maps have never been created before, do so using the current 
		 * number of excitatory nodes as size (if more nodes are added the maps will
		 * grow dynamically). 
		 */
		
		// TODO: Where should these hash map be cleared?
		if (undeterminedClassFiringRateMap == null) 
			undeterminedClassFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());
		
		if (trackClassFiringRateMap == null) 
			trackClassFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());
		
		if (spotClassFiringRateMap == null) 
			spotClassFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());
		
		Main.updateLogPanel("Weights update started", Color.BLACK);

		
		/*
		 * Compute random weights for all the synapses of the excitatory and inhibitory nodes.
		 */
		
		for (Node inhNode : Main.inhNodes) {
			// Number of synapse per neuron that are effectively used.
			int activeSynPerNeuron = inhNode.originalNumOfSynapses - inhNode.terminal.numOfDendrites;			
			
			// Array intended to store only the weights of the synapses that have been changed.
			byte[] sparseWeights;
			int sparseArrayLength = activeSynPerNeuron * inhNode.terminal.numOfNeurons;
			sparseWeights = new byte[sparseArrayLength];
			
			// Float version of the previous array to be send to the hash map storing on the server the weights of the nodes.
			float[] sparseWeightsFloat = new float[sparseArrayLength];
			
			// Array storing the flags which indicate whether the weight corresponding to the synapse should be updated during the training.
			byte[] updateWeightsFlags = new byte[sparseArrayLength];				
			
			// Iterate over all the neurons of the present terminal.
			for (int neuronIndex = 0; neuronIndex < inhNode.terminal.numOfNeurons; neuronIndex++) {
				int weightOffset = 0; // Keep track of how many weights have been updated for a given connection.
				
				// Iterate over all the presynaptic connections of the terminal.
				for (com.example.overmind.Terminal presynapticTerminal : inhNode.terminal.presynapticTerminals) {
					// Iterate over all the synapses coming from any given presynaptic connection.
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = randomNumber.nextFloat();
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] / Constants.MIN_WEIGHT);
						
						/*
						 * The weights of the inhibitory neurons should not updated, not matter whether the synapse is excitatory
						 * or inhibitory. 
						 */
						
						
	        			updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = DONT_UPDATE_WEIGHT;	        			
					}
					
        			weightOffset += presynapticTerminal.numOfNeurons;
				}
			}			
			
			VirtualLayerManager.weightsTable.put(inhNode.virtualID, sparseWeightsFloat);			
				
			inhNode.terminal.newWeights = sparseWeights;
			inhNode.terminal.newWeightsIndexes = new int[] {0};
			inhNode.terminal.updateWeightsFlags = updateWeightsFlags;
											
			VirtualLayerManager.unsyncNodes.add(inhNode);	
		}	
		
		// Algorithm is almost identical for excitatory nodes.
		for (Node excNode : Main.excNodes) {
			
			/*
			 * Add the input sender to the presynaptic connections of the terminals
			 * underlying the excitatory nodes.
			 */			
			
			// Create a Terminal object holding all the info regarding this server,
			// which is the input sender. 
			com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    
			thisApp.numOfNeurons = (short) Constants.MAX_PIC_PIXELS;
			thisApp.numOfSynapses = excNode.terminal.numOfNeurons;
			thisApp.numOfDendrites = 0;
			thisApp.ip = CandidatePicsReceiver.serverIP;
			thisApp.natPort = Constants.APP_UDP_PORT;
			
			excNode.terminal.presynapticTerminals.add(thisApp);
			excNode.terminal.postsynapticTerminals.add(thisApp);
			excNode.terminal.numOfDendrites -= Constants.MAX_PIC_PIXELS;
			
			/*
			 * Create a new array for each node to store the firing rates 
			 * of the neurons if necessary.
			 */
			
			if (!undeterminedClassFiringRateMap.containsKey(excNode.physicalID))
				undeterminedClassFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
			
			if (!trackClassFiringRateMap.containsKey(excNode.physicalID))
				trackClassFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
			
			if (!spotClassFiringRateMap.containsKey(excNode.physicalID))
				spotClassFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
			
			// Here is the part of the algorithm shared by inhibitory and excitatory nodes.			
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
			byte[] sparseWeights;
			int sparseArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
			sparseWeights = new byte[sparseArrayLength];
			float[] sparseWeightsFloat = new float[sparseArrayLength];
			byte[] updateWeightsFlags = new byte[sparseArrayLength];				
			
			for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
				int weightOffset = 0;
				for (com.example.overmind.Terminal presynapticTerminal : excNode.terminal.presynapticTerminals) {
					
					/*
					 * The only differences are here: The presynaptic connection can either be excitatory or inhibitory,
					 * therefore the sign of the weights must be checked first. Additionally, if it is inhibitory, the weight should
					 * not be update during the training session, and therefore the relative flag should be unset. 
					 */
					
					float weightSign = 1;
					for (Node inhNode : Main.inhNodes) {
						if (inhNode.equals(presynapticTerminal)) 
							weightSign = -1;
					}
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = weightSign * randomNumber.nextFloat();
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] / Constants.MIN_WEIGHT);
	        			updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = Main.inhNodes.contains(presynapticTerminal) ? 
	        					DONT_UPDATE_WEIGHT : UPDATE_WEIGHT;
					}
					
					weightOffset += presynapticTerminal.numOfNeurons;
				}
			}			
			
			VirtualLayerManager.weightsTable.put(excNode.virtualID, sparseWeightsFloat);
			
			excNode.terminal.newWeights = sparseWeights;
			excNode.terminal.newWeightsIndexes = new int[] {0};
			excNode.terminal.updateWeightsFlags = updateWeightsFlags;
							
			VirtualLayerManager.unsyncNodes.add(excNode);			
		} 
		
		/* [End of for over excitatory nodes] */
		
		Future<Boolean> future = VirtualLayerManager.syncNodes();		
		
		// Wait for the synchronization process to be completed before proceeding.
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				Main.updateLogPanel("Weights update interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			Main.updateLogPanel("Weights update interrupted", Color.RED);
			return STREAM_INTERRUPTED;
		}
		
		return OPERATION_SUCCESSFUL;
	}
	
	boolean classifyInput(boolean isTrainingSession)  {	
		final boolean ERROR_OCCURRED = false;
		final boolean OPERATION_SUCCESSFUL = true;				
			
		NetworkStimulator networkStimulator = new NetworkStimulator();		
		
		untaggedFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());	
		
		// Give the last terminal to be updated by setSynapticWeights a little bit of time to receive the package.
		try {			
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			Main.updateLogPanel("Simulation interrupted while sleeping", Color.RED);
			return ERROR_OCCURRED;
		}		
				
		Main.updateLogPanel("Analysis started", Color.BLACK);
		
		/*
		 * Get all the grayscale candidates files that must be analyzed. 
		 */
		
		// TODO: retrieved files should be saved in a hash map and then deleted. The hash map should be saved on storage at the end. 		
		String path = new File("").getAbsolutePath();
		if (isTrainingSession) {
			path = path.concat("/resources/pics/training_set");
		} else {
			path = path.concat("/resources/pics/samples");
		}
		File samplesSetDir = new File(path);
		ArrayList<File> sampleSetFiles = new ArrayList<>(Arrays.asList(samplesSetDir.listFiles()));
		
		// Delete unwanted files that may have been included. 
		Iterator<File> iterator = sampleSetFiles.iterator();
		while (iterator.hasNext()) {
			File file = iterator.next();
			if (file.getName().equals(".gitignore"))
				iterator.remove();;
		}
				
		if (sampleSetFiles.size() == 0 | sampleSetFiles == null) {
			Main.updateLogPanel("No sample found", Color.RED);
			return false;
		}
		
		ObjectInputStream objectInputStream = null; // To read the Candidate object from the file stream.
        FileInputStream fileInputStream = null; // To read from the file.
        GrayscaleCandidate[] grayscaleCandidates = new GrayscaleCandidate[sampleSetFiles.size()];        
        
        // Convert the files into objects and save them. 
        for (int i = 0; i < sampleSetFiles.size(); i++) {
        	try {
        		fileInputStream = new FileInputStream(sampleSetFiles.get(i));
        		objectInputStream = new ObjectInputStream(fileInputStream);
        		grayscaleCandidates[i] = (GrayscaleCandidate) objectInputStream.readObject();
        	} catch (ClassNotFoundException | IOException e) {
        		e.printStackTrace();
        	} 
        }        
        
        /*
         * Send the retrieved grayscale candidates to the network.
         */
        
        // Each input layer has its own candidate object which serves as input.
        GrayscaleCandidate[] inputCandidates = new GrayscaleCandidate[Main.excNodes.size()];
        
        // Create an array of nodes from the collection. 
        Node[] inputLayers = new Node[Main.excNodes.size()];
    	Main.excNodes.toArray(inputLayers);    	
    	
    	// Start the thread that handles the incoming spikes.
    	SpikesReceiver spikesReceiver = new SpikesReceiver(isTrainingSession);
    	spikesReceiver.start();      
    	
    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
    		boolean sampleAnalysisFinished = false;   		
        	Arrays.fill(inputCandidates, candidate); // In this implementation the inputs of the nodes are all the same.
        	
    		// If this is not a training session create additional arrays for each node to store 
    		// the firing rates of their neurons in response to the samples. 
    		if (!isTrainingSession) {
    			for (Node excNode : Main.excNodes) {
    				untaggedFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
    			}
    		}
        	
    		// If this is not a training session the same input is sent to the network until its class
        	// has not been determined
        	while (!sampleAnalysisFinished & !shutdown) {
        		currentInputClass = candidate.particleTag;    		

	        	// Stimulate the input layers with the candidate grayscale map.
	        	// TODO: Handle disconnection of node during stimulation.
	        	boolean noErrorRaised = 
	        			networkStimulator.stimulateWithLuminanceMap(Constants.STIMULATION_LENGTH, Constants.DELTA_TIME, inputLayers, inputCandidates);  
	        	if (!noErrorRaised) {
	        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return ERROR_OCCURRED;
	        	}        	
	        	        	
	        	//currentInputClass = NO_INPUT;
	        	
	       	    // Wait before sending the new stimulus according to the PAUSE_LENGTH constant. 
	        	try {
					Thread.sleep(Constants.PAUSE_LENGTH);
				} catch (InterruptedException e) {
					Main.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}     
	        	
	        	if (!isTrainingSession)
	        		System.out.println("Probability " + probability);
	        	
	        	sampleAnalysisFinished = isTrainingSession | probability > 0.3f;
    		}
        	
        	if (!isTrainingSession)
        		System.out.println("Real class: " + candidate.particleTag + " Tentative class: " + tentativeClass);
        }
    	
    	spikesReceiver.shutdown = true;
    	spikesReceiver.socket.close();
    	try {
    		spikesReceiver.join();
    	} catch (InterruptedException e) {
			Main.updateLogPanel("spikesReceiver shutdown interrupted", Color.RED);
			return ERROR_OCCURRED;
    	}
    	
    	untaggedFiringRateMap.clear();
           
		return OPERATION_SUCCESSFUL;				
	}
	
}
