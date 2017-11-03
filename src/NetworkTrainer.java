import java.util.ArrayList;

public class NetworkTrainer {	
	
	/**
	 * Application of the STDP rate based learning rule. 
	 */
	
	public float[] stdpBasedLearning(float[] presynapticRates, float[] postsynapticRates, float[] weights) {
		if (!(presynapticRates.length == postsynapticRates.length & 
				presynapticRates.length == weights.length)) {
			System.out.println("ERROR: length of presynapticRates, postsynapticRates and weights vectors are not the same.");
			return null;
		}
		
		for (int index = 0; index < weights.length; index++) {
			weights[index] += postsynapticRates[index] * (presynapticRates[index] - weights[index]); 
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
				if (!excNode.changeLateralConnectionsOption())
					return false;
			
			/*
			 * If the node satisfies all the requirements, make it 
			 * unavailable to the Overmind server. 
			 */
			
			VirtualLayerManager.availableNodes.remove(excNode);
		}		
				
		return true;
	}	
	
}
