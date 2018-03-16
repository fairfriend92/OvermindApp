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
	// Stores for each node the firing rates in response to an input that must be classified.
	private static ConcurrentHashMap<Integer, float[]> untaggedFiringRateMap;  

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
          			
          			if (!isTrainingSession) {
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
        		if (!isTrainingSession) {
					try {
						threadsDispatcherQueue.offer(new WorkerThread(spikesPacket, spikesBuffer), MuonTeacherConst.DELTA_TIME / 2, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}               		
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
		
		/*
		 * Check that the number of excitatory nodes is equal to that of the 
		 * types of particles into which the pictures can be classified.
		 */
		
		if (Main.excNodes.size() < MuonTeacherConst.NUM_OF_PARTICLES_TYPES) {
			Main.updateLogPanel("Too few exc nodes", Color.RED);
			return false;
		}
		
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
	 * Method used to prepare the network for receiving new weights, either randomized
	 * or read from a previously saved file.
	 * @return True if the operation went smoothly, false otherwise.
	 */
	
	boolean setupLoadWeights() {
		final boolean SETUP_ERROR = false;
		final boolean OPERATION_SUCCESSFUL = true;
		
		// Create a Terminal object holding all the info regarding this server,
		// which is the input sender. 
		com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    
		thisApp.numOfNeurons = (short) MuonTeacherConst.MAX_PIC_PIXELS;
		thisApp.numOfSynapses = Short.MAX_VALUE;
		thisApp.numOfDendrites = 0;
		thisApp.ip = Constants.USE_LOCAL_CONNECTION ? VirtualLayerManager.localIP : VirtualLayerManager.serverIP;
		thisApp.serverIP = thisApp.ip;
		thisApp.natPort = MuonTeacherConst.APP_UDP_PORT;
		
		for (Node excNode : Main.excNodes) {
			// Firstly reset the connections of the terminal if these had been modified before. 
			Main.removeThisAppFromConnections(excNode.terminal);
			
			// Connect the excNode to the application. 
			excNode.terminal.presynapticTerminals.add(thisApp);
			excNode.terminal.postsynapticTerminals.add(thisApp);
			excNode.terminal.numOfDendrites -= MuonTeacherConst.MAX_PIC_PIXELS;
					
			VirtualLayerManager.unsyncNodes.add(excNode);			
		}		

		Future<Boolean> future = VirtualLayerManager.syncNodes();		
		
		// Wait for the synchronization process to be completed before proceeding.
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				Main.updateLogPanel("Setup of exc node interrupted", Color.RED);
				return SETUP_ERROR;
			}
		} catch (InterruptedException | ExecutionException e) {
			Main.updateLogPanel("Setup of exc interrupted", Color.RED);
			return SETUP_ERROR;
		}
		
		return OPERATION_SUCCESSFUL;
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
					 * The presynaptic connection can either be excitatory or inhibitory,
					 * therefore the sign of the weights must be checked first. Additionally, if it is inhibitory, the weight should
					 * not be updated during the training session, and therefore the relative flag should be unset. 
					 */
					
					float weightSign = 1;
					for (Node inhNode : Main.inhNodes) {
						if (inhNode.equals(presynapticTerminal)) 
							weightSign = -1;
					}
					
					boolean presynTerminalIsApp = presynapticTerminal.ip.equals(presynapticTerminal.serverIP);
					
					float probOfConnection;
					if (weightSign == -1) 
						probOfConnection = MuonTeacherConst.INH_TO_EXC_PERCT;
					else if (presynTerminalIsApp)
						probOfConnection = 1.0f;
					else 
						probOfConnection = MuonTeacherConst.EXC_TO_INH_PERCT;
					
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {												
						float random = randomNumber.nextFloat();
						float weight = random < probOfConnection ? random : 0.0f;
						
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								weightSign * weight;
						
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] / MuonTeacherConst.MIN_WEIGHT);
	        			
						updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex + weightOffset] = 
	        					weightSign == -1 ? DONT_UPDATE_WEIGHT : UPDATE_WEIGHT;
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
	
	/**
	 * Method which set the flags of the excitatory nodes so that no further 
	 * learning takes place. 
	 * @return true if no error occurred, false is the sending of the updated 
	 * terminal info was interrupted. 
	 */
	
	boolean stopLearning() {
		final boolean STREAM_INTERRUPTED = false;
		final boolean OPERATION_SUCCESSFUL = true;
		
		for (Node excNode : Main.excNodes) {
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites;
			int sparseArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
						
			excNode.terminal.updateWeightsFlags = new byte[sparseArrayLength];
			VirtualLayerManager.unsyncNodes.add(excNode);		
		}
		
		Future<Boolean> future = VirtualLayerManager.syncNodes();		
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				Main.updateLogPanel("Weights reset interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			Main.updateLogPanel("Weights reset interrupted", Color.RED);
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
				iterator.remove();
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
        		grayscaleCandidates[i] = grayscaleCandidate; // Unnecessary if the candidates must be ordered by class. Comment it out.        		
        		
        		// Add the last candidate to the ArrayList corresponding to its kind. 
        		candidatesCollections.get(grayscaleCandidate.particleTag).add(grayscaleCandidate);
        	} catch (ClassNotFoundException | IOException e) {
        		e.printStackTrace();
        	} 
        }   
                
        /*
         * During the training session order the pictures so that pictures of different
         * types are interleaved with each other. 
         */
        
        if (isTrainingSession) {       	
	        for (int typeIndex = 0; typeIndex < MuonTeacherConst.NUM_OF_PARTICLES_TYPES; typeIndex++) {
	        	int classIndex = typeIndex == 0 ? 1 : 3;
	        	Iterator<GrayscaleCandidate> candidatesIterator = candidatesCollections.get(classIndex).iterator();
	        	int offset = 0;
	        	while (candidatesIterator.hasNext()) {
	        		grayscaleCandidates[typeIndex + offset * MuonTeacherConst.NUM_OF_PARTICLES_TYPES] = 
	        				candidatesIterator.next();
	        		offset++;	        		
	        	}
	        }   
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
    	float[] dummyInput = new float[MuonTeacherConst.MAX_PIC_PIXELS];
    	Arrays.fill(dummyInput, 0.0f);
    	long postprocessingTime = 0; // Time take to post-process the firing rate vectors collected. 
    	GrayscaleCandidate dummyCandidate = // A Candidate object which contains a picture completely blank.
    			new GrayscaleCandidate(dummyInput, MuonTeacherConst.UNDETERMINED);
    	
    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
        	int allowedIterations = MuonTeacherConst.MIN_ITERATIONS;
    		boolean sampleAnalysisFinished = false;  
    		
    		/*
    		 * Prepare the inputs for this iteration. 
    		 * If this is the training phase, all nodes receive a blank input except for 
    		 * the population corresponding to the particle type of the current candidate.
    		 * 
    		 * If this is not the training session, all the nodes receive the same picture. 
    		 */
    		
    		if (isTrainingSession) {
    			Arrays.fill(inputCandidates, dummyCandidate);
    			inputCandidates[candidate.particleTag == 1 ? 0 : 1] = candidate; // TODO: Make function to convert tag into index.
    		} else {
    			Arrays.fill(inputCandidates, candidate);   
    		}
    		
    		for (Node excNode : Main.excNodes) { 
    			// If this is not a training session create additional arrays for each node to store 
	    		// the firing rates of their neurons in response to the samples. 
    			if (!isTrainingSession) {
    				untaggedFiringRateMap.put(excNode.physicalID, new float[excNode.terminal.numOfNeurons]);
    			}
    		}
    		    		
    		int iteration = 0, // Times the same input has been presented to the network. 
    				guessedClass = -1; 
    		double finalProbability = 0.0f; // Probability associated with the guessed class.    
    		double[] meanProbabilities = new double[MuonTeacherConst.NUM_OF_PARTICLES_TYPES]; // Temporary probs.
    		int[] meanSamples = new int[MuonTeacherConst.NUM_OF_PARTICLES_TYPES]; // Number of samples used to average the temp probs. 
        	long postprocessingStartTime = 0; // Time at which the post-processing start.    		    
    		
    		// Break the loop if the analysis has been interrupted or the application shutdown or 
    		// the sample has been thoroughly analyzed. 
        	while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished) {
        		currentInputClass = candidate.particleTag;   		
        		iteration++; 
	        	
	        	long tmpTime = postprocessingStartTime != 0 ?  
	        		(System.nanoTime() - postprocessingStartTime) / MuonTeacherConst.MILLS_TO_NANO_FACTOR : 0;
	        	postprocessingTime = tmpTime < MuonTeacherConst.DELTA_TIME ? MuonTeacherConst.DELTA_TIME  : tmpTime;
        		
	        	float pauseLength = isTrainingSession ? 0 : MuonTeacherConst.PAUSE_LENGTH;
	        	
	        	// Stimulate the input layers with the candidate grayscale map.
	        	// TODO: Handle disconnection of node during stimulation.
        		ArrayList<Future<?>> inputSenderFutures = 
	        			networkStimulator.stimulateWithLuminanceMap(
	        					MuonTeacherConst.STIMULATION_LENGTH, pauseLength, MuonTeacherConst.DELTA_TIME, inputLayers, inputCandidates);  
	        	if (inputSenderFutures == null) {
	        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return ERROR_OCCURRED;
	        	}        	
	        	  						        	
	        	boolean trainingDone = false, sampleClassified = false; // Flags that govern the flow. 
	        	
	        	/*
	        	 * Put this thread to sleep while the input is being sent but wake up before all the inputs
	        	 * have been sent so that there is still time to do a little bit of post-processing. 
	        	 */
	        	
	        	try {
					Thread.sleep((long)(MuonTeacherConst.PAUSE_LENGTH + MuonTeacherConst.STIMULATION_LENGTH) - postprocessingTime);
				} catch (InterruptedException e) {
					Main.updateLogPanel("Stimulation interrupted during pause", Color.RED);
					return ERROR_OCCURRED;
				}  			
					        		        	
	        	postprocessingStartTime = System.nanoTime();
	        	
	        	if (!isTrainingSession & !shutdown) {
	        		// Get the firing rates vector of all the nodes, 
	        		// each of which corresponds to a different type of particle.
	        		float[][] untaggedFiringRates = new float[MuonTeacherConst.NUM_OF_PARTICLES_TYPES][];
	        		for (int typeIndex = 0; typeIndex < MuonTeacherConst.NUM_OF_PARTICLES_TYPES; typeIndex++) {
	        			untaggedFiringRates[typeIndex] = untaggedFiringRateMap.get(Main.excNodes.get(typeIndex).physicalID);
	        		}
   					
	        		/*
	        		 * Compute which of the node presents the highest activity.
	        		 */
	        		
	        		int highestRateNodeNumber = 0;
    				double highestRateVectorLength = 0.0f, totalLength = 0.0f;    	
    				double[] vectorLengths = new double[MuonTeacherConst.NUM_OF_PARTICLES_TYPES];
    				
					for (int typeIndex = 0; typeIndex < MuonTeacherConst.NUM_OF_PARTICLES_TYPES; typeIndex++) {
						int numOfNeurons = Main.excNodes.get(typeIndex).terminal.numOfNeurons;
						for (int neuronIndex = 0; neuronIndex < numOfNeurons; neuronIndex++) {
    						vectorLengths[typeIndex] += Math.pow(untaggedFiringRates[typeIndex][neuronIndex], 2); 
						}
						vectorLengths[typeIndex] = Math.sqrt(vectorLengths[typeIndex]);
						totalLength += vectorLengths[typeIndex];
						if (vectorLengths[typeIndex] > highestRateVectorLength) {
							highestRateVectorLength = vectorLengths[typeIndex];
							highestRateNodeNumber = typeIndex;
						}
    				}    			
    				
					// The probability with which the guess has been made. 
    				meanProbabilities[highestRateNodeNumber] += highestRateVectorLength / totalLength;
    				meanSamples[highestRateNodeNumber]++; // How many times the same class has been associated with the input. 
					
    				/*
    				 * Compute which class best describes the current input and the probability related
    				 * to the guess. 
    				 */
    				
    				if (iteration >= allowedIterations) {
    					double maxProbability = meanProbabilities[0];
    					int tentativeClass = 1;
    					
    					// Compute which probability is the higher among the different classes. 
    					for (int typeIndex = 0; typeIndex < MuonTeacherConst.NUM_OF_PARTICLES_TYPES; typeIndex++) {
    						if (meanProbabilities[typeIndex] >= maxProbability) {
    							maxProbability = meanProbabilities[typeIndex];
    							tentativeClass = typeIndex;
    						}
    					}
    					
    					maxProbability /= meanSamples[tentativeClass];
    	    					
    					if (maxProbability > 0.6f | allowedIterations >= MuonTeacherConst.MAX_ITERATIONS) {
    						sampleClassified = true;
	    					finalProbability = maxProbability;
	    					guessedClass = tentativeClass;
    					} else {
    						allowedIterations += MuonTeacherConst.ITERATION_INCREMENT;
    					}    					
    				}
	        	} else if (!shutdown) {			        	
		        	trainingDone = (currentInputClass == MuonTeacherConst.TRACK & iteration == 1) | 
		        			(currentInputClass == MuonTeacherConst.SPOT & iteration == 1);
		        		        			        			        	
		        	System.out.println("Class " + currentInputClass);   
	        	}	 
	        		        	
	        	// Wait for all the InputSender threads to finish by retrieving their Future objects.		
	    		try {
	    			for (Future<?> inputSenderFuture : inputSenderFutures)
	    				inputSenderFuture.get();
	    		} catch (InterruptedException | ExecutionException e) {
	    			e.printStackTrace();
	    			return false;
	    		}		        	
	        	
	        	sampleAnalysisFinished = sampleClassified | trainingDone;
    		}
        	/* [End of while ( !analysisInterrupt.get() & !shutdown & !sampleAnalysisFinished)] */
        	
        	totalGuess++;
        	guessedClass = guessedClass == 0 ? 1 : 3; // TODO: Make function that convert tag in type class.
        	if (guessedClass == currentInputClass) {
        		rightGuess++;        		
        	}
        	
        	if (!isTrainingSession)
        		System.out.println("Real class: " + candidate.particleTag + " Tentative class: " + guessedClass 
        				+ " finalProbability " + finalProbability + " Success rate: " + (rightGuess / totalGuess));
        }   
    	
    	/* Shutdown operations */    	  
    	
    	// Shutdown worker threads
    	boolean terminationSuccessful = true;
    	
    	spikesReceiver.shutdown = true;
    	if (spikesReceiver.socket != null)
    		spikesReceiver.socket.close();
    	try {
    		spikesReceiver.join(100);
    	} catch (InterruptedException e) {
    		terminationSuccessful = false;
			Main.updateLogPanel("spikesReceiver shutdown interrupted", Color.RED);
    	}
    	
    	networkStimulator.inputSenderService.shutdown();
    	try {
			terminationSuccessful &= networkStimulator.inputSenderService.awaitTermination(100, TimeUnit.MILLISECONDS);
			if (!terminationSuccessful) {
				Main.updateLogPanel("inputSenderService didn't shutdown in time", Color.RED);
			}
		} catch (InterruptedException e) {
			terminationSuccessful = false;
			Main.updateLogPanel("inputSenderService shutdown interrupted", Color.RED);
		}
    	
    	// Clear hash maps.
    	Collection<DatagramSocket> socketsCollection = networkStimulator.socketsHashMap.values();
    	for (DatagramSocket oldSocket : socketsCollection) {
    		oldSocket.close();
    	}
    	networkStimulator.socketsHashMap.clear();    	
    	untaggedFiringRateMap.clear();   
          
		return terminationSuccessful;				
	}
	
}
