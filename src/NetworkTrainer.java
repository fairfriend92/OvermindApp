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
import java.util.Collection;
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
	private static volatile int currentInputClass = MuonTeacherConst.UNDETERMINED;
		
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	// Constants local to this class.
	private final byte UPDATE_WEIGHT = (byte)1;
	private final byte DONT_UPDATE_WEIGHT = (byte)0;
	private final static int NO_INPUT = -1;
	
	// Flags that control the execution of the code
	static boolean shutdown = false;
	static AtomicBoolean analysisInterrupt = new AtomicBoolean(false);
					
	/**
	 * Class that waits for UDP packets to arrive at a specific port and
	 * update the firing rates of the neurons that produced the spikes.  
	 */
	
	private static class MuonTeacherSpikesReceiver extends Thread {	
		
		private BlockingQueue<WorkerThread> threadsDispatcherQueue = new ArrayBlockingQueue<>(128);
		boolean shutdown = false; // This shutdown should be independent of the main one. 
		boolean isTrainingSession = false;
		DatagramSocket socket;
		
		MuonTeacherSpikesReceiver(boolean isTrainingSession) {
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
        			int ipHashCode = (spikesPacket.getAddress().toString().substring(1) + "/" + spikesPacket.getPort()).hashCode();          			
        			int numOfNeurons = VirtualLayerManager.nodesTable.get(ipHashCode).terminal.numOfNeurons;         		
        		        			
          			float[] meanFiringRates = null;
          			
          			if (isTrainingSession) {
              			// Retrieve from the collection of hash maps the one associated to the current input class. 
              			// From that has map, retrieve the array associated to the node identified by ipHashCode. 
            			meanFiringRates = taggedFiringRateMaps.get(currentInputClass).get(ipHashCode);	
        			} else {
        				// Vector of the firing rates that must be compared. 
        				meanFiringRates = untaggedFiringRateMap.get(ipHashCode);  
        			}
          			
        			assert meanFiringRates != null;        			
        			
        			// Iterating over the the neurons that produced the spike trains.
        			for (int neuronIndex = 0; neuronIndex < numOfNeurons; neuronIndex++) { 
        				int byteIndex = neuronIndex / 8;
        				
        				// If the current neuron had emitted a spike, increase the firing rate using a simple moving average algorithm. 
        				meanFiringRates[neuronIndex] += ((spikesBuffer[byteIndex] >> (neuronIndex - byteIndex * 8)) & 1) == 1 ? 
        						+ MuonTeacherConst.MEAN_RATE_INCREMENT * (1 - meanFiringRates[neuronIndex]) : 
        							- MuonTeacherConst.MEAN_RATE_INCREMENT * meanFiringRates[neuronIndex];	       	
        			}	        		
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
						int ipHashCode = (workerThread.spikesPacket.getAddress().toString().substring(1) + 
								"/" + workerThread.spikesPacket.getPort()).hashCode();
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
	    		socket = new DatagramSocket(MuonTeacherConst.APP_UDP_PORT);
	    		socket.setTrafficClass(MuonTeacherConst.IPTOS_THROUGHPUT);
	    	} catch (SocketException e) {
	    		e.printStackTrace();
	    	}
	    	assert socket != null;    	
	    	
	    	while (!shutdown) {
	    		// Receive the datagram packet with the latest spikes array.        		
        		DatagramPacket spikesPacket = null;
    			byte[] spikesBuffer = new byte[MuonTeacherConst.MAX_DATA_BYTES];
        		try {
        			spikesPacket = new DatagramPacket(spikesBuffer, MuonTeacherConst.MAX_DATA_BYTES);
        			socket.receive(spikesPacket);
        			spikesBuffer = spikesPacket.getData();
        		} catch (IOException e) {
        			System.out.println("spikesReceiver socket is closed");
        			break;
        		}
        		        		
        		// Create a new Runnable to do any kind of post-processing on the spikes sent
        		// by the terminal.
				try {
					threadsDispatcherQueue.offer(new WorkerThread(spikesPacket, spikesBuffer), MuonTeacherConst.DELTA_TIME / 2, TimeUnit.MILLISECONDS);
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
			
			if (excNode.terminal.numOfDendrites < MuonTeacherConst.MAX_PIC_PIXELS) {
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
			
			/*
			if (inhNode.terminal.numOfDendrites < MuonTeacherConst.MAX_PIC_PIXELS) {
				Main.updateLogPanel("Node " + inhNode.terminal.ip + " doesn't have enough dendrites for pic.", Color.RED);
				return false;
			}
			*/
			
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
		
		// Create a Terminal object holding all the info regarding this server,
		// which is the input sender. 
		com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    
		thisApp.numOfNeurons = (short) MuonTeacherConst.MAX_PIC_PIXELS;
		thisApp.numOfSynapses = Short.MAX_VALUE;
		thisApp.numOfDendrites = 0;
		thisApp.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
		thisApp.natPort = MuonTeacherConst.APP_UDP_PORT;
				
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

			/*
			 * Add the input sender to the presynaptic connections of the terminals.
			 */						
					
			/*
			inhNode.terminal.presynapticTerminals.add(thisApp);
			inhNode.terminal.numOfDendrites -= MuonTeacherConst.MAX_PIC_PIXELS;
			*/
			
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
					
					/*
					 * Depending on the nature of the connection (inhibitory or excitatory), there is a certain
					 * probability of establishing the connection. 
					 */				
					
					float probOfConnection;
					if (presynapticTerminal.ip.equals(presynapticTerminal.serverIP)) // Input connections are always non zero.
						probOfConnection = 1.0f;
					else // Connection of inh neurons are only excitatory. 
						probOfConnection = MuonTeacherConst.EXC_TO_INH_PERCT;
					
					// Iterate over all the synapses coming from any given presynaptic connection.
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						float random = randomNumber.nextFloat();
						float weight = random < probOfConnection ? random : 0.0f;
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = weight;
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] / MuonTeacherConst.MIN_WEIGHT);
						
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
						
			excNode.terminal.presynapticTerminals.add(thisApp);
			excNode.terminal.postsynapticTerminals.add(thisApp);
			excNode.terminal.numOfDendrites -= MuonTeacherConst.MAX_PIC_PIXELS;
			
			/*
			 * Create a new array for each node to store the firing rates 
			 * of the neurons if necessary.
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
					 * not be updated during the training session, and therefore the relative flag should be unset. 
					 */
					
					float weightSign = 1;
					for (Node inhNode : Main.inhNodes) {
						if (inhNode.equals(presynapticTerminal)) 
							weightSign = -1;
					}
					
					float probOfConnection;
					if (weightSign == -1) 
						probOfConnection = MuonTeacherConst.INH_TO_EXC_PERCT;
					else if (presynapticTerminal.ip.equals(presynapticTerminal.serverIP))
						probOfConnection = 1.0f;
					else 
						probOfConnection = MuonTeacherConst.EXC_TO_INH_PERCT;
					
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						float random = randomNumber.nextFloat();
						float weight = random < probOfConnection ? random : 0.0f;
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = weightSign * weight;
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] / MuonTeacherConst.MIN_WEIGHT);
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
        for (int i = 0; i < Main.excNodes.size(); i++) {
        	inputLayers[i] = Main.excNodes.get(i);
        }
        
    	// Start the thread that handles the incoming spikes.
    	MuonTeacherSpikesReceiver spikesReceiver = new MuonTeacherSpikesReceiver(isTrainingSession);
    	spikesReceiver.start();      
    	
    	float rightGuess = 0.0f, totalGuess = 0.0f, deltaFactor = 1.0f;
    	long postprocessingTime = 0; // Time take to post-process the firing rate vectors collected. 
    	
    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
        	int allowedIterations = 5;

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
    		    		
    		int iteration = 0, // Times the same input has been presented to the network. 
    				guessedClass = -1; 
    		float finalProbability = 0.0f; // Probability associated with the guessed class.     		
    		float[] meanProbabilities = new float[5]; // Temporary probs.
    		int[] meanSamples = new int[5]; // Number of samples used to average the temp probs. 
    		    		    	    		    			        	
    		// Break the loop if the analysis has been interrupted or the application shutdown or 
    		// the sample has been thoroughly analyzed. 
        	while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished) {
        		currentInputClass = candidate.particleTag;   		
        		iteration++; 
        		
	        	// Stimulate the input layers with the candidate grayscale map.
	        	// TODO: Handle disconnection of node during stimulation.
        		ArrayList<Future<?>> inputSenderFutures = 
	        			networkStimulator.stimulateWithLuminanceMap(
	        					MuonTeacherConst.STIMULATION_LENGTH, MuonTeacherConst.PAUSE_LENGTH, MuonTeacherConst.DELTA_TIME, inputLayers, inputCandidates);  
	        	if (inputSenderFutures == null) {
	        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return ERROR_OCCURRED;
	        	}        	
	        	
        		        						        	
	        	float firingRateDelta = 0, norm = 0.01f; // Variable used to gauge how the learning is proceeding.
	        	boolean trainingDone = false, sampleClassified = false; // Flags that govern the flow. 
	        	long postprocessingStartTime = 0; // Time at which the post-processing start. 
	        	
	        	/*
	        	 * Put this thread to sleep while the input is being sent but wake up before the all the inputs
	        	 * have been sent so that there is still time to do a little bit of post-processing. 
	        	 */
	        	
	        	System.out.println("postprocessingTime " + postprocessingTime);
	        	try {
					Thread.sleep((long)(MuonTeacherConst.PAUSE_LENGTH + MuonTeacherConst.STIMULATION_LENGTH) - 2 * postprocessingTime);
				} catch (InterruptedException e) {
					Main.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}  			
					        		        	
	        	postprocessingStartTime = System.nanoTime();
	        	
	        	// Pick up a random node to gauge the progression of the learning algorithm. 
	        	int nodeID = -1;
	        	if (!shutdown)
	        		nodeID = Main.excNodes.get(0).physicalID; // TODO: Do the same thing for every excitatory node. 
	        	int numOfNeurons = Main.excNodes.get(0).terminal.numOfNeurons;
	        	
	        	if (!isTrainingSession & !shutdown) {
	        		// Vector of the firing rates that must be compared. 
    				float[] untaggedFiringRate = untaggedFiringRateMap.get(nodeID); 
    				
    				// Float vector with each element indicating how similar the firing rate is to that of the relative class.         				
    				float[] distances = new float[5];     
    				
    				// Double vector storing the different classes of firing rates that untaggedFiringRate can belong to.         				
    				float[][] taggedFiringRates = new float[5][];      				
    				for (int i = 0; i < 5; i++)
    					taggedFiringRates[i] = taggedFiringRateMaps.get(i).get(nodeID); 

    				// Compute the distance between untaggedFiringRates and the class specific firing rate vectors.
    				for (int neuronIndex = 0; neuronIndex < numOfNeurons; neuronIndex++) {
    					for (int classIndex = 0; classIndex < 5; classIndex++) 
    						distances[classIndex] +=
    						(float)Math.pow(taggedFiringRates[classIndex][neuronIndex] - untaggedFiringRate[neuronIndex], 2);
    				}    			
    				
    				/*
    				 * Compute which class best describes the current input and the probability related
    				 * to the guess. 
    				 * 
    				 * If the input has been presented a number of times < allowedIterations, then keep 
    				 * track of how many times each one of the classes is associated with the input and the probability
    				 * with which that happens every time. 
    				 * Otherwise compute the final probability by averaging over the probabilities with which every 
    				 * class has been associated with the input at every iteration. 
    				 */
    				
    				if (iteration < allowedIterations) {
	    				float minDistance = distances[0], totalDistance = 0.0f;    		
	    				int tentativeClass = 0;
	    				
	    				// Compute which of the class firing rate vectors is closer to the one produced.
	    				for (int classIndex = 0; classIndex < 5; classIndex++) {  
	    					if (classIndex == 1 | classIndex == 3) { // TODO: Temp solution until we use more pictures from all the classes. 
		    					totalDistance += distances[classIndex];
		    					if (distances[classIndex] < minDistance) {
		    						minDistance = distances[classIndex];
		    						tentativeClass = classIndex;
		    					}
	    					}
	    				}    
	    				
	    				// The probability with which the guess has been made. 
	    				meanProbabilities[tentativeClass] += 1 - minDistance / totalDistance;
	    				meanSamples[tentativeClass]++; // How many times the same class has been associated with the input. 
    				} else {
    					float maxProbability = meanProbabilities[0];
    					int tentativeClass = 0;
    					
    					// Compute which probability is the higher among the different classes. 
    					for (int classIndex = 0; classIndex < 5; classIndex++) {
    						if (classIndex == 1 | classIndex == 3) { // TODO: Temp solution until all classes are present.
	    						if (meanProbabilities[classIndex] > maxProbability) {
	    							maxProbability = meanProbabilities[classIndex];
	    							tentativeClass = classIndex;
	    						}
    						}
    					}
    					
    					maxProbability /= meanSamples[tentativeClass];
    	    					
    					if (maxProbability > 0.6f | allowedIterations == MuonTeacherConst.MAX_ITERATIONS) {
    						sampleClassified = true;
	    					finalProbability = maxProbability;
	    					guessedClass = tentativeClass;
    					} else {
    						allowedIterations += 5;
    					}    					
    				}
	        	} else if (!shutdown) {	     	
	        		// Compute how much the prototype firing rate vector for the current class 
		        	// and the chosen node has changed.
		        	float[] oldFiringRate = oldFiringRateMap.get(nodeID);
		        	float[] newFiringRate = taggedFiringRateMaps.get(currentInputClass).get(nodeID);
		        	for (int i = 0; i < newFiringRate.length; i++) {
		        		firingRateDelta += Math.abs(newFiringRate[i] - oldFiringRate[i]);
		        		norm += oldFiringRate[i];
		        	}
		        	
		        	// Update the old firing rate vector.
		        	System.arraycopy(newFiringRate, 0, oldFiringRate, 0, newFiringRate.length);	
		        	firingRateDelta /= norm;	
		        	
		        	/*
		        	 * A new input is selected only if the firing rates of the neurons in response to
		        	 * the current one stabilize, which means that the vector of firing rates, sampled
		        	 * at the beginning and at the end of the i-th iteration, must be roughly the same.
		        	 * 
		        	 * To ascertain this, the difference between the vector at the 2 points in time is computed
		        	 * and it is compared with a moving threshold. 
		        	 */
		        	
		        	// If the difference is under the threshold.
		        	if (firingRateDelta < MuonTeacherConst.BASE_DELTA * deltaFactor) {
		        		deltaFactor = deltaFactor - 0.1f > MuonTeacherConst.MIN_FACTOR ? 
		        				deltaFactor - 0.1f : deltaFactor - 0.0f; // Lower the threshold.
		        		trainingDone = true;
		        	} else if (iteration >= allowedIterations) { // If the threshold is too high.
		        		if (deltaFactor == MuonTeacherConst.MAX_FACTOR) { // Make the threshold higher if possible.
		        			allowedIterations = allowedIterations + 5 < MuonTeacherConst.MAX_ITERATIONS ?
		        					allowedIterations + 5 : MuonTeacherConst.MAX_ITERATIONS;
		        		} else { // If not, iterate over the same input a higher number of times. 
		        			deltaFactor = deltaFactor + 0.1f < MuonTeacherConst.MAX_FACTOR ?
		        					deltaFactor + 0.1f : MuonTeacherConst.MAX_FACTOR;
		        		}
		        	}		  
		        	
		        	trainingDone = trainingDone | iteration == MuonTeacherConst.MAX_ITERATIONS;
		        			        			        	
		        	System.out.println("firingRateDelta " + firingRateDelta + " deltaFactor " + 
		        	deltaFactor + " allowedIterations " + allowedIterations + " iteration " + iteration);      	
		        	
		        	// Wait for all the InputSender threads to finish by retrieving their Future objects.		
		    		try {
		    			for (Future<?> inputSenderFuture : inputSenderFutures)
		    				inputSenderFuture.get();
		    		} catch (InterruptedException | ExecutionException e) {
		    			e.printStackTrace();
		    			return false;
		    		}		        	
	        	}	 
	        	long tmpTime = (System.nanoTime() - postprocessingStartTime) / MuonTeacherConst.MILLS_TO_NANO_FACTOR;
	        	postprocessingTime = tmpTime == 0 ? 1 : tmpTime;
	        	
	        	sampleAnalysisFinished = sampleClassified | trainingDone;
    		}
        	/* [End of while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished)] */
        	
        	totalGuess++;
        	if (guessedClass == currentInputClass) {
        		rightGuess++;        		
        	}
        	
        	if (!isTrainingSession)
        		System.out.println("Real class: " + candidate.particleTag + " Tentative class: " + guessedClass 
        				+ " finalProbability " + finalProbability + " Success rate: " + (rightGuess / totalGuess));
        }   
    	
    	/* Shutdown operations */    	  
    	
    	// Shutdown worker threads
    	spikesReceiver.shutdown = true;
    	if (spikesReceiver.socket != null)
    		spikesReceiver.socket.close();
    	try {
    		spikesReceiver.join();
    	} catch (InterruptedException e) {
			Main.updateLogPanel("spikesReceiver shutdown interrupted", Color.RED);
			return ERROR_OCCURRED;
    	}
    	
    	// Clear hash maps.
    	Collection<DatagramSocket> socketsCollection = networkStimulator.socketsHashMap.values();
    	for (DatagramSocket oldSocket : socketsCollection) {
    		oldSocket.close();
    	}
    	networkStimulator.socketsHashMap.clear();    	
    	untaggedFiringRateMap.clear();   
    	oldFiringRateMap.clear();
          
		return OPERATION_SUCCESSFUL;				
	}
	
}
