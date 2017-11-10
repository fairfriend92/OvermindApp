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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NetworkTrainer {	
	
	// A collection for each node to store the presynaptic and postsynaptic spike trains. 	
	static ConcurrentHashMap<Integer, SpikeTrainsBuffers> spikeTrainsBuffersMap = null;  
	
	// Object used to synchronize the worker thread of SpikesReceiver with thread on which 
	// NetworkTrainer runs
	private final static Object lock = new Object();
	
	/**
	 * Simple class that stores two buffers, one for the presynaptic spike trains and 
	 * of for the postsynaptic ones. 
	 */
	
	static class SpikeTrainsBuffers {
		ArrayList<byte[]> presynapticSpikeTrains;
		ArrayList<byte[]> postsynapticSpikeTrains;
		short numOfSpikeTrains = 0;
		
		SpikeTrainsBuffers(ArrayList<byte[]> presynapticSpikeTrains, ArrayList<byte[]> postsynapticSpikeTrains) {
			this.presynapticSpikeTrains = presynapticSpikeTrains;
			this.postsynapticSpikeTrains = postsynapticSpikeTrains;
		}
	}
	
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
        			SpikeTrainsBuffers spikeTrainsBuffers = spikeTrainsBuffersMap.get(ipHashCode); // Get a reference to the object storing the buffers.
	        		spikeTrainsBuffers.postsynapticSpikeTrains.add(spikesBuffer); // Add the latest spike array to the postsynaptic spike trains buffer. 
	        		
	        		System.out.println("" + spikeTrainsBuffers.postsynapticSpikeTrains.size());
	        		
	        		/* If the buffer is full it's time to compute the firing rate and apply the learning rule. */
	        			        		
	        		if (spikeTrainsBuffers.postsynapticSpikeTrains.size() == Constants.STIMULATION_LENGTH / Constants.DELTA_TIME) {
	        			System.out.println("test");
	        			
						Node node = VirtualLayerManager.nodesTable.get(ipHashCode); // Get a reference to the node which  sent the packet.
	        			
	        			float[] presynapticFiringRates = 
	        					SpikeInputCreator.computeFiringRate(spikeTrainsBuffers.presynapticSpikeTrains, spikeTrainsBuffers.numOfSpikeTrains);
	        			float[] postsynapticFiringRates =
	        					SpikeInputCreator.computeFiringRate(spikeTrainsBuffers.postsynapticSpikeTrains, node.terminal.numOfNeurons);
	        			
	        			int offset = 0; // The offset accounts for the synaptic weights that come before those that carry the stimulus. 
        				for (int nodeIndex = 0; nodeIndex < node.presynapticNodes.size() - 1; nodeIndex++)
        					offset += node.presynapticNodes.get(nodeIndex).terminal.numOfNeurons;        	

        				float[] totalWeights = VirtualLayerManager.weightsTable.get(node.virtualID); // Get the weights of all the neurons of the node. 
        				float[] partialWeights = new float[spikeTrainsBuffers.numOfSpikeTrains]; // Store the weights of the input synapses for a given neuron.  
        				float[] finalWeights = new float[spikeTrainsBuffers.numOfSpikeTrains * node.terminal.numOfNeurons]; // Store the weights of ALL the input synapses.
        				int[] weightsIndexes = new int[spikeTrainsBuffers.numOfSpikeTrains * node.terminal.numOfNeurons]; 
	        			
        				/* Apply the learning rule for each neuron using its firing rate and that of the input synapses. */
        				        				
	        			for (int neuronIndex = 0; neuronIndex < node.terminal.numOfNeurons; neuronIndex++) {
	        				System.arraycopy(totalWeights, neuronIndex * node.originalNumOfSynapses + offset, partialWeights, 0, partialWeights.length);
	        				partialWeights = stdpBasedLearning(presynapticFiringRates, postsynapticFiringRates[neuronIndex], partialWeights);
	        				System.arraycopy(partialWeights, 0, finalWeights, spikeTrainsBuffers.numOfSpikeTrains * neuronIndex, partialWeights.length);
	        				for (int weightIndex = 0; weightIndex < partialWeights.length; weightIndex++)
	        					weightsIndexes[neuronIndex * partialWeights.length + weightIndex] = neuronIndex * node.originalNumOfSynapses + offset + weightIndex;
	        			}
	        			
	        			/* Update the weights */
	        			
	        			node.terminal.newWeights = finalWeights;
	        			node.terminal.newWeightsIndexes = weightsIndexes;		        			
	        			VirtualLayerManager.unsyncNodes.add(node);	  
	        			
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
			}
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
	 * Application of the STDP rate based learning rule. 
	 */
	
	private static float[] stdpBasedLearning(float[] presynapticRates, float postsynapticRate, float[] weights) {
		if (presynapticRates.length != weights.length) {
			System.out.println("ERROR: length of presynapticRates and weights vectors are not the same.");
			return null;
		}
		
		for (int index = 0; index < weights.length; index++) {
			weights[index] += postsynapticRate * (presynapticRates[index] - weights[index]); 
		}
				
		return weights;
	}
	
	/**
	 * The method checks if the chosen nodes satisfy the necessary conditions
	 * to build a network such as the specific one needed by this app, MuonDetectorTeacher. 
	 */
	
	public boolean checkNetworkTopology() {
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
	 * the synaptic weights of each node to their default values. 
	 */
	
	static class SetSynapticWeights implements Runnable {
		
		@Override
		public void run() {		
			Random randomNumber = new Random();	
			
			Main.updateLogPanel("Updating weights...", Color.BLACK);
			
			/*
			 * The inhibitory neurons receive input only from the excitatory ones 
			 * (no lateral connections), therefore the synaptic weights are all positive. 
			 */	
			
			for (Node inhNode : Main.inhNodes) {
				// Compute the number of synapses that is being used. 
				int activeSynapses = (inhNode.originalNumOfSynapses - inhNode.terminal.numOfDendrites) * inhNode.terminal.numOfNeurons;
				
				// Create an array holding the synaptic weights that need to be changed. 
				float[] weights = new float[activeSynapses];
				
				// Create an array holding the indexes of the weights that are going to be changed.
				int[] weightsIndexes = new int[activeSynapses];
				
				// Update all the weights. 
				for (int i = 0; i < weights.length; i++) {
					weights[i] = randomNumber.nextFloat();
					weightsIndexes[i] = i;
				}		
						
				// Update the references of the inhNode object.
				inhNode.terminal.newWeights = weights; // TODO: Assigning references is enough or should we use System.arraycopy()?
				inhNode.terminal.newWeightsIndexes = weightsIndexes;
				
				// The weights of the synapses that are not active should be zero. Therefore, create an array
				// padded with a number of zeros equal to that of the unused synapses.			
				float[] allWeights = new float[inhNode.originalNumOfSynapses * inhNode.terminal.numOfNeurons];
				System.arraycopy(weights, 0, allWeights, 0, weights.length);
				
				// Update the entry of the hash table.
				VirtualLayerManager.weightsTable.put(inhNode.virtualID, allWeights);
				
				// Send the new weights to the terminal.
				VirtualLayerManager.unsyncNodes.add(inhNode);
			}	
			
			for (Node excNode : Main.excNodes) {
				int activeSynapses = (excNode.originalNumOfSynapses - excNode.terminal.numOfDendrites) * excNode.terminal.numOfNeurons;
				float[] weights = new float[activeSynapses];
				int[] weightsIndexes = new int[activeSynapses];
				
				int offset = 0; // The last synapse for a given neuron that was considered.
				
				/*
				 * Go over all the presynaptic connections and, depending on whether the presynaptic nodes
				 * are excitatory or inhibitory, compute random weights with either the plus or the minus sign.			
				 */
				
				// If lateral connections are enabled by NetworkTrainer they are not the first presynaptic connections!
				
				// The first presynaptic connection of an exc. node is itself, since for these nodes 
				// lateral connections are always enabled. Since these connections don't count as a separate
				// node, they need to be handled independently. 
				for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
					for (int weightIndex = 0; weightIndex < excNode.terminal.presynapticTerminals.get(0).numOfNeurons; weightIndex++) {
						weights[neuronIndex * excNode.terminal.numOfNeurons + weightIndex] = randomNumber.nextFloat();		
						
						weightsIndexes[neuronIndex * excNode.terminal.numOfNeurons + weightIndex] = 
								neuronIndex * excNode.terminal.numOfNeurons + weightIndex;
					}
				}
				
				offset += excNode.terminal.presynapticTerminals.get(0).numOfNeurons;
				
				// Each presynaptic node is either excitatory or inhibitory, and consequently 
				// the sign of the weight change. 
				for (Node presynapticNode : excNode.presynapticNodes) {
					int wieghtSign = Main.inhNodes.contains(presynapticNode) ? - 1 : 1;
					
					// Once the sign is computed, the synaptic weights of each neuron must be updated.
	 				for (int neuronIndex = 0; neuronIndex < excNode.terminal.numOfNeurons; neuronIndex++) {
	 					
	 					// To chance all the synapses iterate over the neurons of the presynaptic terminal. 
						for (int weightIndex = offset; weightIndex < (offset + presynapticNode.terminal.numOfNeurons); weightIndex++) {							
							weights[neuronIndex * excNode.terminal.numOfNeurons + weightIndex] = 
									wieghtSign * randomNumber.nextFloat();		
							
							weightsIndexes[neuronIndex * excNode.terminal.numOfNeurons + weightIndex] = 
									neuronIndex * excNode.terminal.numOfNeurons + weightIndex;
						} 
						
						/* [End of for over weights] */					
						
					} 
	 				
	 				/* [End of for over neurons] */
	 				 				
	 				// The offset is increased to account for the synapses of a given neuron that have already been served. 
	 				offset += presynapticNode.terminal.numOfNeurons; 				
				} 
				
				/* [End of for over presynaptic nodes] */					
				
				excNode.terminal.newWeights = weights;
				excNode.terminal.newWeightsIndexes = weightsIndexes;
				
				float[] allWeights = new float[excNode.originalNumOfSynapses * excNode.terminal.numOfNeurons];
				System.arraycopy(weights, 0, allWeights, 0, weights.length);
				
				VirtualLayerManager.weightsTable.put(excNode.virtualID, weights);
				
				VirtualLayerManager.unsyncNodes.add(excNode);			
			} 
			
			/* [End of for over excitatory nodes] */
			
			VirtualLayerManager.syncNodes();			
			
			Main.updateLogPanel("All weights updated", Color.BLACK);
		}
	}
	
	static class StartTraining implements Callable<Boolean> {
		
		@Override
		public Boolean call() { 			
			Main.updateLogPanel("Training started", Color.BLACK);
			
			NetworkStimulator networkStimulator = new NetworkStimulator();
			
			spikeTrainsBuffersMap = new ConcurrentHashMap<>(Main.excNodes.size()); // Size the hash map. 
			
			/*
			 * Add the input sender to the presynaptic connections of the terminals
			 * underlying the excitatory nodes. Add the server to the postsynaptic connections
			 * and create a buffer for the spike trains of each node.
			 */			
			
			com.example.overmind.Terminal thisApp = new com.example.overmind.Terminal();    			
			for (Node excNode : Main.excNodes) {
				// Create the buffer which will store the spike train produced by excNode. 
				ArrayList<byte[]> presynapticSpikeTrains = new ArrayList<byte[]>(Constants.STIMULATION_LENGTH / Constants.DELTA_TIME);
				ArrayList<byte[]> postsynapticSpikeTrains = new ArrayList<byte[]>(Constants.STIMULATION_LENGTH / Constants.DELTA_TIME);			
				SpikeTrainsBuffers nodeSpikeTrains = 
						new SpikeTrainsBuffers(presynapticSpikeTrains, postsynapticSpikeTrains);			
				spikeTrainsBuffersMap.put(excNode.physicalID, nodeSpikeTrains);
				
				// Create a Terminal object holding all the info regarding this server,
				// which is the input sender. 
				thisApp.numOfNeurons = (short) Constants.MAX_PIC_PIXELS;
				thisApp.numOfSynapses = 0;
				thisApp.numOfDendrites = excNode.terminal.numOfNeurons;
				thisApp.ip = CandidatePicsReceiver.serverIP;
				thisApp.natPort = Constants.APP_UDP_PORT;
				
				excNode.terminal.presynapticTerminals.add(thisApp);
				excNode.terminal.postsynapticTerminals.add(thisApp);
				excNode.terminal.numOfDendrites -= Constants.MAX_PIC_PIXELS;
				// TODO: decrease the synapses by excNode.terminal.numOfNeurons? See VirtualLayerManager todo note. 
				VirtualLayerManager.unsyncNodes.add(excNode);
			}
			
			VirtualLayerManager.syncNodes();		
			
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
	    	SpikesReceiver spikesReceiver = new SpikesReceiver();
	    	spikesReceiver.start();
	        
	    	/* Repeat the training for all the candidates in the training set */
	    	
	    	for (GrayscaleCandidate candidate : grayscaleCandidates) {
	        	Arrays.fill(inputCandidates, candidate); // In this implementation the inputs of the nodes are all the same.         	
	        	
	        	// Stimulate the input layers with the candidate grayscale map.
	        	boolean noErrorRaised = 
	        			networkStimulator.stimulateWithLuminanceMap(Constants.STIMULATION_LENGTH, Constants.DELTA_TIME, inputLayers, inputCandidates);  
	        	if (!noErrorRaised) {
	        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
	        		return false;
	        	}
	        	
	        	System.out.println("Pause started");
	        	        	
	        	long pauseStartTime = System.nanoTime();        	
	
	        	/*
	       	    // Wait before sending the new stimulus according to the PAUSE_LENGTH constant. 
	        	while ((System.nanoTime() - pauseStartTime) / Constants.NANO_TO_MILLS_FACTOR < 
	        			Constants.PAUSE_LENGTH) {    
	        	}      
	        	*/        	
	        	
	        	synchronized(lock) {
	        		try {
						lock.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
	        	}
	        	        	
	        	System.out.println("Pause length: " + (System.nanoTime() - pauseStartTime) / Constants.NANO_TO_MILLS_FACTOR + " ms");     
	        	 	
	        	
	        	VirtualLayerManager.syncNodes();
	        }
	        
	        spikesReceiver.shutdown = true;  
	        try {
				spikesReceiver.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}        
	        spikeTrainsBuffersMap.clear();
	        for (Node excNode : Main.excNodes) {
	        	Main.removeThisAppFromConnections(excNode.terminal);
	        	VirtualLayerManager.unsyncNodes.add(excNode);
	        }
	        VirtualLayerManager.syncNodes();
	        
			return true;		
		}
	}
	
}
