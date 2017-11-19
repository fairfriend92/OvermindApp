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
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class NetworkTrainer {	
	// Hash map used to store the buffers holding the spike trains of the nodes. 
	private static ConcurrentHashMap<Integer, ArrayList<byte[]>> spikeTrainsBuffersMap;
	
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	// Constants local to this class.
	private final static byte UPDATE_WEIGHT = (byte)1;
	private final static byte DONT_UPDATE_WEIGHT = (byte)0;
	
	
	/**
	 * Class that waits for UDP packets to arrive at a specific port and
	 * stores their contents into the spike trains buffers of the nodes they come from.  
	 */
	
	private static class SpikesReceiver extends Thread {
		
		private volatile static int numOfFullBuffers = 0;
		boolean shutdown = false;
		
		/**
		 * Inner class that implements Runnable and takes care of adding the latest
		 * spikes array to the spike trains buffer of the sender node. If the buffer is
		 * full, the worker thread computes the firing rate based on the stored 
		 * spike trains. 
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
        			
    				/* Add the spikes array to the spike trains of the node which sent the packet. */
        			
					int ipHashCode = spikesPacket.getAddress().hashCode();
        			ArrayList<byte[]> postsynapticSpikeTrains = spikeTrainsBuffersMap.get(ipHashCode); // Get a reference to the object the buffer.
	        		postsynapticSpikeTrains.add(spikesBuffer); // Add the latest spike array to the postsynaptic spike trains buffer. 
	        		
	        		System.out.println("" + postsynapticSpikeTrains.size());
	        		
	        		/* If the buffer is full it's time to compute the firing rate. */
	        			        		
	        		if (postsynapticSpikeTrains.size() == Constants.STIMULATION_LENGTH / Constants.DELTA_TIME) {
	        			System.out.println("test");
	        			
						Node node = VirtualLayerManager.nodesTable.get(ipHashCode); // Get a reference to the node which  sent the packet.
	        			
	        			float[] postsynapticFiringRates =
	        					SpikeInputCreator.computeFiringRate(postsynapticSpikeTrains, node.terminal.numOfNeurons);	        			
	        	        			
	        			/* Unlock the thread which sent the stimulus if the all the firing rates have been computed */
	        			
	        			numOfFullBuffers++;
	        			if (numOfFullBuffers == Main.excNodes.size()) {
	        				numOfFullBuffers = 0;
	        				synchronized(lock) {
	        					lock.notify();
	        				}
	        			}
	        		}
        		}
        		/* [End of if (spikesPacket != null)] */
			}
    		/* [End of run()] */
		}
		
		@Override
		public void run() {
			super.run();
			
			ExecutorService workerThreadsExecutor = Executors.newCachedThreadPool();			
						
	    	/* Create the datagram socket used to read the incoming spikes. */
	    	
	    	DatagramSocket spikesReceiver = null;
	    	try {
	    		spikesReceiver = new DatagramSocket(Constants.APP_UDP_PORT);
	    		spikesReceiver.setTrafficClass(Constants.IPTOS_THROUGHPUT);
	    	} catch (SocketException e) {
	    		e.printStackTrace();
	    	}
	    	assert spikesReceiver != null;    	
	    	
	    	while (!shutdown) {
	    		// Receive the datagram packet with the latest spikes array.        		
        		DatagramPacket spikesPacket = null;
    			byte[] spikesBuffer = new byte[Constants.MAX_DATA_BYTES];
        		try {
        			spikesPacket = new DatagramPacket(spikesBuffer, Constants.MAX_DATA_BYTES);
        			spikesReceiver.receive(spikesPacket);
        			spikesBuffer = spikesPacket.getData();
        		} catch (IOException e) {
        			e.printStackTrace();
        		}
        		
        		// Create a worker thread to add the the latest spikes to the sender node buffer
        		// and eventually compute the firing rate. 
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
	    	
	    	spikesReceiver.close();	    	
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
		 * Compute random weights for all the synapses of the excitatory and inhibitory nodes.
		 */
		
		for (Node inhNode : Main.inhNodes) {
			// Number of synapse per neuron that are effectively used.
			int activeSynPerNeuron = inhNode.originalNumOfSynapses - inhNode.terminal.numOfSynapses;			
			
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
				// Iterate over all the presynaptic connections of the terminal.
				for (com.example.overmind.Terminal presynapticTerminal : inhNode.terminal.presynapticTerminals) {
					// Iterate over all the synapses coming from any given presynaptic connection.
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] = randomNumber.nextFloat();
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] / Constants.MIN_WEIGHT);
						
						/*
						 * The weights of the inhibitory neurons should not updated, not matter whether the synapse is excitatory
						 * or inhibitory. 
						 */
						
						
	        			updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex] = DONT_UPDATE_WEIGHT;
					}
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
			int activeSynPerNeuron = excNode.originalNumOfSynapses - excNode.terminal.numOfSynapses;
			byte[] sparseWeights;
			int sparseArrayLength = activeSynPerNeuron * excNode.terminal.numOfNeurons;
			sparseWeights = new byte[sparseArrayLength];
			float[] sparseWeightsFloat = new float[sparseArrayLength];
			byte[] updateWeightsFlags = new byte[sparseArrayLength];				
			
			for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
				for (com.example.overmind.Terminal presynapticTerminal : excNode.terminal.presynapticTerminals) {
					
					/*
					 * The only differences are here: The presynaptic connection can either be excitatory or inhibitory,
					 * therefore the sign of the weights must be checked first. Additionaly, if it is inhibitory, the weight should
					 * not be update during the training session, and therefore the relative flag should be unset. 
					 */
					
					
					int weightSign = Main.inhNodes.contains(presynapticTerminal) ? - 1 : 1;
					for (int weightIndex = 0; weightIndex < presynapticTerminal.numOfNeurons; weightIndex++) {
						sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] = weightSign * randomNumber.nextFloat();
						sparseWeights[neuronIndex * activeSynPerNeuron + weightIndex] = 
								(byte)(sparseWeightsFloat[neuronIndex * activeSynPerNeuron + weightIndex] / Constants.MIN_WEIGHT);
	        			updateWeightsFlags[neuronIndex * activeSynPerNeuron + weightIndex] = Main.inhNodes.contains(presynapticTerminal) ? 
	        					DONT_UPDATE_WEIGHT : UPDATE_WEIGHT;
					}
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
			e.printStackTrace();
		}
		
		return OPERATION_SUCCESSFUL;
	}
	
	boolean startTraining()  {	
		final boolean ERROR_OCCURRED = false;
		final boolean STREAM_INTERRUPTED = false;
		final boolean OPERATION_SUCCESSFUL = true;
					
		Main.updateLogPanel("Training started", Color.BLACK);
		
		NetworkStimulator networkStimulator = new NetworkStimulator();			
		
		/*
		 * Add the input sender to the presynaptic connections of the terminals
		 * underlying the excitatory nodes.
		 */			
		
		com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    			
		for (Node excNode : Main.excNodes) {
			// Create the buffer which will store the spike train produced by excNode. 
			/*
			ArrayList<byte[]> postsynapticSpikeTrains = new ArrayList<byte[]>(Constants.STIMULATION_LENGTH / Constants.DELTA_TIME);		
			spikeTrainsBuffersMap = new ConcurrentHashMap<>(Main.excNodes.size());
			spikeTrainsBuffersMap.put(excNode.physicalID, postsynapticSpikeTrains);
			*/
			
			// Create a Terminal object holding all the info regarding this server,
			// which is the input sender. 
			thisApp.numOfNeurons = (short) Constants.MAX_PIC_PIXELS;
			thisApp.numOfSynapses = 0;
			thisApp.numOfDendrites = excNode.terminal.numOfNeurons;
			thisApp.ip = CandidatePicsReceiver.serverIP;
			thisApp.natPort = Constants.APP_UDP_PORT;
			
			excNode.terminal.presynapticTerminals.add(thisApp);
			//excNode.terminal.postsynapticTerminals.add(thisApp);
			excNode.terminal.numOfDendrites -= Constants.MAX_PIC_PIXELS;
			// TODO: decrease the synapses by excNode.terminal.numOfNeurons? See VirtualLayerManager todo note. 
			VirtualLayerManager.unsyncNodes.add(excNode);
		}
		
		Future<Boolean> future = VirtualLayerManager.syncNodes();	
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				Main.updateLogPanel("TCP stream interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		
		/*
		 * Get all the grayscale candidates files used for training. 
		 */
		
		String path = new File("").getAbsolutePath();
		path = path.concat("/resources/pics/training_set");
		File trainingSetDir = new File(path);
		File[] trainingSetFiles = trainingSetDir.listFiles();
		
		if (trainingSetFiles.length == 0 | trainingSetFiles == null) {
			Main.updateLogPanel("No training set found", Color.RED);
			return false;
		}
		
		ObjectInputStream objectInputStream = null; // To read the Candidate object from the file stream.
        FileInputStream fileInputStream = null; // To read from the file.
        GrayscaleCandidate[] grayscaleCandidates = new GrayscaleCandidate[trainingSetFiles.length];        
        
        // Convert the files into objects and save them. 
        for (int i = 0; i < trainingSetFiles.length; i++) {
        	try {
        		fileInputStream = new FileInputStream(trainingSetFiles[i]);
        		objectInputStream = new ObjectInputStream(fileInputStream);
        		grayscaleCandidates[i] = (GrayscaleCandidate) objectInputStream.readObject();
        	} catch (ClassNotFoundException | IOException e) {
        		e.printStackTrace();
        	} 
        }        
        
        /*
         * Train the network using all the retrieved grayscale candidates.
         */
        
        // Each input layer has its own candidate object which serves as input.
        GrayscaleCandidate[] inputCandidates = new GrayscaleCandidate[Main.excNodes.size()];
        
        // Create an array of nodes from the collection. 
        Node[] inputLayers = new Node[Main.excNodes.size()];
    	Main.excNodes.toArray(inputLayers);    	
    	
    	// Start the thread that asynchronously store the incoming spike trains.
    	/*
    	SpikesReceiver spikesReceiver = new SpikesReceiver();
    	spikesReceiver.start();
    	*/
        
    	/* Repeat the training for all the candidates in the training set */
    	
    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
        	Arrays.fill(inputCandidates, candidate); // In this implementation the inputs of the nodes are all the same.         	
        	
        	// Stimulate the input layers with the candidate grayscale map.
        	boolean noErrorRaised = 
        			networkStimulator.stimulateWithLuminanceMap(Constants.STIMULATION_LENGTH, Constants.DELTA_TIME, inputLayers, inputCandidates);  
        	if (!noErrorRaised) {
        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
        		return ERROR_OCCURRED;
        	}        	
        	        	
        	long pauseStartTime = System.nanoTime();        	

        	
       	    // Wait before sending the new stimulus according to the PAUSE_LENGTH constant. 
        	while ((System.nanoTime() - pauseStartTime) / Constants.NANO_TO_MILLS_FACTOR < 
        			Constants.PAUSE_LENGTH) {    
        	}      
        
        	/*
           	synchronized(lock) {
        		try {
					lock.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
        	}
        	        	
        	System.out.println("Pause length: " + (System.nanoTime() - pauseStartTime) / Constants.NANO_TO_MILLS_FACTOR + " ms");    	  
        	*/      	 	
        	
        }
    	
    	/* 
    	 * Delete the input sender from the list of presynaptic connections.
    	 */
        
    	/*
        spikesReceiver.shutdown = true;  
        try {
			spikesReceiver.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}        
        spikeTrainsBuffersMap.clear();
        */
        for (Node excNode : Main.excNodes) {
        	Main.removeThisAppFromConnections(excNode.terminal);
        	VirtualLayerManager.unsyncNodes.add(excNode);
        }	        
        future = VirtualLayerManager.syncNodes();	
		try {
			Boolean syncSuccessful = future.get();
			if (!syncSuccessful) {
				Main.updateLogPanel("TCP stream interrupted", Color.RED);
				return STREAM_INTERRUPTED;
			}
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
        
		return OPERATION_SUCCESSFUL;				
	}
	
}
