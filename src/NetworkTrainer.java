import java.awt.Color;
import java.awt.List;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkTrainer {	

	// Collection of hash maps, each of which is storing for every node the array 
	// of firing rates produced in response to a specific input. 
	private static ArrayList<ConcurrentHashMap<Integer, float[]>> taggedFiringRateMaps = 
			new ArrayList<ConcurrentHashMap<Integer, float[]>>(5);
	
	// Stores for each node the firing rates in response to an input that must be classified.
	private static ConcurrentHashMap<Integer, float[]> untaggedFiringRateMap;  
	
	private static ConcurrentHashMap<Integer, float[]> oldFiringRateMap;

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
	
	// Flags that control the execution of the code
	static boolean shutdown = false;
	static AtomicBoolean analysisInterrupt = new AtomicBoolean(false);
					
	/**
	 * Class that waits for UDP packets to arrive at a specific port and
	 * update the firing rates of the neurons that produced the spikes.  
	 */
	
	private static class SpikesReceiver extends Thread {	
		
		private BlockingQueue<WorkerThread> threadsDispatcherQueue = new ArrayBlockingQueue<>(128);
		boolean shutdown = false; // This shutdown should be independent of the main one. 
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
        			
        			// Double vector storing the different classes of firing rates that untaggedFiringRate can belong to.         				
    				float[][] taggedFiringRates = new float[5][];      				
    				for (int i = 0; i < 5; i++)
    					taggedFiringRates[i] = taggedFiringRateMaps.get(i).get(ipHashCode);  
    				    				        			
        			/*
        			 * If the network is being trained, compute the mean rate of the neurons for each 
        			 * input class and save it in the appropriate hash map. Otherwise, compare the rate with
        			 * that previously saved to determine to which class the current input belongs to. 
        			 */
        			
        			if (isTrainingSession) {
              			float[] meanFiringRates = null;
	        			
              			// Retrieve from the collection of hash maps the one associated to the current input class. 
              			// From that has map, retrieve the array associated to the node identified by ipHashCode. 
            			meanFiringRates = taggedFiringRateMaps.get(currentInputClass).get(ipHashCode);
            			
            			assert meanFiringRates != null;
            			
	        			// Iterating over the the neurons that produced the spike trains.
	        			for (int neuronIndex = 0; neuronIndex < numOfNeurons; neuronIndex++) { 
	        				int byteIndex = neuronIndex / 8;
	        				
	        				// If the current neuron had emitted a spike, increase the firing rate using a simple moving average algorithm. 
	        				meanFiringRates[neuronIndex] += ((spikesBuffer[byteIndex] >> (neuronIndex - byteIndex * 8)) & 1) == 1 ? 
	        						+ Constants.MEAN_RATE_INCREMENT * (1 - meanFiringRates[neuronIndex]) : 
	        							- Constants.MEAN_RATE_INCREMENT * meanFiringRates[neuronIndex];	        				
	        					        				
	        			}	        			
        			} else {
        				// Vector of the firing rates that must be compared. 
        				float[] untaggedFiringRate = untaggedFiringRateMap.get(ipHashCode);        				
        				        				        				
        				// Float vector with each element indicating how similar the firing rate is to that of the relative class.         				
        				float[] distances = new float[5];        
        				
        				for (int index = 0; index < numOfNeurons; index++) { 
	        				int byteIndex = index / 8;
	        				
	        				untaggedFiringRate[index] += ((spikesBuffer[byteIndex] >> (index - byteIndex * 8)) & 1) == 1 ? 
	        						+ Constants.MEAN_RATE_INCREMENT * (1 - untaggedFiringRate[index]) : 
	        							- Constants.MEAN_RATE_INCREMENT * untaggedFiringRate[index];
	        				
	        				for (int i = 0; i <5; i++)
	        					distances[i] += Math.pow(taggedFiringRates[i][index] - untaggedFiringRate[index], 2);
	        			}              				
        				
        				float norm = 0, tentativeMaxDistance = 0;
        				
        				for (int i = 0; i < 5; i++) {        				
	        				distances[i] = (float) Math.sqrt(distances[i] / numOfNeurons);
	        				
	        				// The probability for each class is computed.
	        				distances[i] = 1 - distances[i];
	        				
	        				norm += distances[i];
	        				
	        				// The tentative class is determined and the overall probability is computed.
	        				if (distances[i] > tentativeMaxDistance) {
	        					tentativeMaxDistance = distances[i];
	        					tentativeClass = i;
	        					probability = distances[i];
	        				}
        				}    
        				
        				probability /= norm;
        			}
        			/* [End of if (isTrainingSession)] */
        		}
        		/* [End of if (spikesPacket != null)] */
			}
    		/* [End of run] */
		}
		/* [End of inner class] */        		
		
		/**
		 * Inner class whose job is that of creating a new thread for each node and to dispatch
		 * the incoming Runnables from the run() method of the outer class to said threads. 
		 * @author rodolfo
		 *
		 */
		
		private class ThreadsDispatcher implements Runnable {
			
			private ExecutorService workerThreadsExecutor = Executors.newFixedThreadPool(Main.excNodes.size());	
			private HashMap<Integer, Future<?>> futuresMap = new HashMap<>(Main.excNodes.size());
		
			@Override
			public void run() {
				while (!shutdown) {
					WorkerThread workerThread = null;
					try {
						workerThread = threadsDispatcherQueue.poll(1, TimeUnit.SECONDS); // Polling is necessary so that the operation doesn't block an eventual shutdown. 
					} catch (InterruptedException e) {
						e.printStackTrace();
					}	
															
					if (workerThread != null) {
						int ipHashCode = workerThread.spikesPacket.getAddress().hashCode();
						Future<?> future = futuresMap.get(ipHashCode);
						
						// If a thread for the node corresponding to ipHashCode was created before,
						// wait for its task to complete. 
						if (future != null) {
							try {
								future.get(); // Worker thread has no blocking operation, so no need to poll. 
							} catch (InterruptedException | ExecutionException e) {
								e.printStackTrace();
							}
						} 

						// Dispatch a new thread and store the associated future in the hash map. 
						future = workerThreadsExecutor.submit(workerThread);
						futuresMap.put(ipHashCode, future);						
					}
					
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
		    	
		    	futuresMap.clear();
			}
									
		}
		
		@Override
		public void run() {
			super.run();			
			
			ExecutorService threadsDispatcherService = Executors.newSingleThreadExecutor();
			threadsDispatcherService.execute(new ThreadsDispatcher());
									
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
        		        		
        		// Create a new Runnable to do any kind of post-processing on the spikes sent
        		// by the terminal.
        		if (currentInputClass != NO_INPUT) // Do so only an input is currently being sent to the network.
					try {
						threadsDispatcherQueue.offer(new WorkerThread(spikesPacket, spikesBuffer), Constants.DELTA_TIME / 2, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}               		
	    	}
	    	
	    	/* Shutdown executor. */
	    	
	    	threadsDispatcherService.shutdown();	    	
	    	try {
	    		boolean workerThreadsExecutorIsShutdown = threadsDispatcherService.awaitTermination(2, TimeUnit.SECONDS);
	    		if (!workerThreadsExecutorIsShutdown) {
	    			System.out.println("ERROR: Failed to shutdown threads dispatcher executor.");
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
		
		for (int i = 0; i < 5; i++) {
			taggedFiringRateMaps.add(new ConcurrentHashMap<>(Main.excNodes.size()));
		}		
		
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
			 * of the neurons if necessary, and the distances between firing rates.
			 */
			
			for (ConcurrentHashMap<Integer, float[]> hashMap : taggedFiringRateMaps) {
				if (!hashMap.containsKey(excNode.physicalID))
					hashMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
			}			
			
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
	        			updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = weightSign == -1 ? 
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
		analysisInterrupt = new AtomicBoolean(false);
		
		NetworkStimulator networkStimulator = new NetworkStimulator();		
		
		untaggedFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());	
		oldFiringRateMap = new ConcurrentHashMap<>(Main.excNodes.size());	
		
		// Give the last terminal to be updated by setSynapticWeights a little bit of time to receive the package.
		if (isTrainingSession) {
			try {			
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				Main.updateLogPanel("Simulation interrupted while sleeping", Color.RED);
				return ERROR_OCCURRED;
			}		
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
        
        // Each ArrayList contains only the candidate of a certain kind 
        ArrayList<ArrayList<GrayscaleCandidate>> candidatesCollections = 
        		new ArrayList<ArrayList<GrayscaleCandidate>>(5); // 5 are the different classes of input.   
        for (int i = 0; i < 5; i++)
        	candidatesCollections.add(new ArrayList<GrayscaleCandidate>(sampleSetFiles.size() / 5)); // Here we assume that the 5 classes are equally represented. 
        
        // Collection of all the candidates.
        GrayscaleCandidate[] grayscaleCandidates = new GrayscaleCandidate[sampleSetFiles.size()];        
        
        // Convert the files into objects and save them. 
        for (int i = 0; i < sampleSetFiles.size(); i++) {
        	try {
        		fileInputStream = new FileInputStream(sampleSetFiles.get(i));
        		objectInputStream = new ObjectInputStream(fileInputStream);
        		GrayscaleCandidate grayscaleCandidate = (GrayscaleCandidate) objectInputStream.readObject();
        		
        		// Add the last candidate to the ArrayList corresponding to its kind. 
        		candidatesCollections.get(grayscaleCandidate.particleTag).add(grayscaleCandidate);
        	} catch (ClassNotFoundException | IOException e) {
        		e.printStackTrace();
        	} 
        }   
        
        /*
         * Copy the candidates in one single array in a way so that candidates of 
         * the same kind are adjacent.
         */
        
        int offset = 0;
        for (int i = 0; i < 5; i++) {
        	// The single collection of candidates of class i. 
        	GrayscaleCandidate[] candidatesCollection = new GrayscaleCandidate[candidatesCollections.get(i).size()];
        	candidatesCollection = candidatesCollections.get(i).toArray(candidatesCollection);
        	
        	System.arraycopy(candidatesCollection, 0, grayscaleCandidates, offset, candidatesCollections.get(i).size());
        	offset += candidatesCollections.get(i).size();
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
    	
    	float rightGuess = 0.0f, totalGuess = 0.0f;
    	
    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
    		boolean sampleAnalysisFinished = false;   		
        	Arrays.fill(inputCandidates, candidate); // In this implementation the inputs of the nodes are all the same.	        		    		
    		
    		for (Node excNode : Main.excNodes) { 
    			// If this is not a training session create additional arrays for each node to store 
	    		// the firing rates of their neurons in response to the samples. 
    			if (!isTrainingSession) {
    				untaggedFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
    			}
    			// Otherwise create arrays to store the firing rate of a certain input class at the point
    			// in time before a new input was sent. 
    			else {
    				oldFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
    			}
    		}
    			        	
    		// Break the loop if the analysis has been interrupted or the application shutdown or 
    		// the sample has been thoroughly analyzed. 
        	while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished) {
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
	        	/*
	        	try {
					Thread.sleep(Constants.PAUSE_LENGTH);
				} catch (InterruptedException e) {
					Main.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}     
				*/
	        	
	        	float firingRateDelta = 0;
	        	
	        	if (!isTrainingSession) {
	        		System.out.println("Probability " + probability);
	        	} else {	        	
	        		// Pick up a random node to gauge the progression of the learning algorithm. 
		        	int nodeID = Main.excNodes.get(0).physicalID;
		        	
		        	// Compute how much the prototype firing rate vector for the current class 
		        	// and the chosen node has changed.
		        	float[] oldFiringRate = oldFiringRateMap.get(nodeID);
		        	float[] newFiringRate = taggedFiringRateMaps.get(currentInputClass).get(nodeID);
		        	for (int i = 0; i < newFiringRate.length; i++) {
		        		firingRateDelta += Math.abs(newFiringRate[i] - oldFiringRate[i]);
		        	}
		        	
		        	// Update the old firing rate vector.
		        	System.arraycopy(newFiringRate, 0, oldFiringRate, 0, newFiringRate.length);	
		        	
		        	System.out.println("firingRateDelta " + firingRateDelta);      					        	
	        	}
	        	
	        	sampleAnalysisFinished = probability > 0.2013f | (firingRateDelta != 0 & firingRateDelta < 1.5);
    		}
        	
        	totalGuess++;
        	if (tentativeClass == currentInputClass)
        		rightGuess++;
        	if (!isTrainingSession)
        		System.out.println("Real class: " + candidate.particleTag + " Tentative class: " + tentativeClass + " Success rate: " + (rightGuess / totalGuess));
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
    	oldFiringRateMap.clear();
          
		return OPERATION_SUCCESSFUL;				
	}
	
}
