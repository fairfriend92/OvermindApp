import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Random;

public class NetworkTrainer {	
	
	/**
	 * Application of the STDP rate based learning rule. 
	 */
	
	public float[] stdpBasedLearning(float[] presynapticRates, float postsynapticRate, float[] weights) {
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
	
	public void setSynapticWeights() {
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
	
	public boolean startTraining() {	
		
		NetworkStimulator networkStimulator = new NetworkStimulator();
		
		/*
		 * Add the input sender to the presynaptic connections of the terminals
		 * underlying the excitatory nodes. 
		 */
		
		for (Node excNode : Main.excNodes) {
			// Create a Terminal object holding all the info regarding this server,
			// which is the input sender. 
			com.example.overmind.Terminal server = new com.example.overmind.Terminal();
			server.numOfNeurons = (short) Constants.MAX_PIC_PIXELS;
			server.numOfSynapses = excNode.terminal.numOfNeurons;
			server.numOfDendrites = server.numOfSynapses;
			server.ip = CandidatePicsReceiver.serverIP;
			server.natPort = Constants.UDP_PORT;
			
			excNode.terminal.presynapticTerminals.add(server);
			excNode.terminal.numOfDendrites -= Constants.MAX_PIC_PIXELS;
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
        		System.out.println(e);
        	} 
        }        
        
        /*
         * Train the network using all the retrieved grayscale candidates.
         */
        
        // Each input layer has its own candidate object which serves as input.
        GrayscaleCandidate[] inputCandidates = new GrayscaleCandidate[Main.excNodes.size()];
        
        // At each new iteration of the training session send a different grayscale map to the input layers.
        for (GrayscaleCandidate candidate : grayscaleCandidates) {
        	Arrays.fill(inputCandidates, candidate); // In this implementation the inputs of the nodes are all the same. 
        	Node[] inputLayers = new Node[Main.excNodes.size()];
        	Main.excNodes.toArray(inputLayers);
        	
        	// Stimulate the input layers with the candidate grayscale map.
        	boolean noErrorRaised = 
        			networkStimulator.stimulateWithLuminanceMap(Constants.STIMULATION_LENGTH, Constants.DELTA_TIME, inputLayers, inputCandidates);  
        	if (!noErrorRaised) {
        		Main.updateLogPanel("Error occurred during the stimulation", Color.RED);
        		return false;
        	}
        	
        	// Wait before sending the new stimulus according to the PAUSE_LENGTH constant. 
        	long finishingTime = System.nanoTime();
        	while ((System.nanoTime() - finishingTime) / Constants.NANO_TO_MILLS_FACTOR < 
        			Constants.PAUSE_LENGTH) {
        		// TODO: Here we read the output of the nodes and build the rates to do the learning.  
        	}
        }
		
		return true;		
	}
	
}
