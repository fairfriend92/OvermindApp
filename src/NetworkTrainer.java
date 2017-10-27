
public class NetworkTrainer {
	
	public static float[] STDPbasedLearning(float[] presynapticRates, float[] postsynapticRates, float[] weights) {
		// If the lengths of the vectors are not the same, exit with error.
		if (!(presynapticRates.length == postsynapticRates.length & 
				presynapticRates.length == weights.length)) {
			return null;
		}
		
		for (int index = 0; index < weights.length; index++) {
			weights[index] += postsynapticRates[index] * (presynapticRates[index] - weights[index]); 
		}
				
		return weights;
	}

}
