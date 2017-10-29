
public class NetworkTrainer {
	
	/**
	 * Appication of the STDP rate based learning rule. 
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

}
