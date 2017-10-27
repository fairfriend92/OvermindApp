import java.util.ArrayList;

/**
 * Class containing methods that pertain to the input made of spikes which is sent
 * to the network. 
 * @author rodolfo
 *
 */

public class SpikeInputCreator {
	
	/**
	 * Create a spike input from a map of pixel luminance
	 */
	
	public static byte[] createFromLuminance(float[] grayscalePixels) {
		byte[] spikeInput;
		short lengthInBytes; // How many bytes are needed to represent the spike input?
		
		// Each float is the luminance of a pixel and each pixel corresponds to a synapse
		lengthInBytes = (short)(grayscalePixels.length % 8 == 0 ? grayscalePixels.length / 8 :
			grayscalePixels.length / 8 + 1);
		
		/* 
		 * The intesity of a pixel represents the probability for a given synapse to 
		 * receive a spike. Hence, compare the intensity with a random number between 1 and 0 and,
		 * if it's greater than it, set the bit corresponding to the synapse.
		 */
		
		spikeInput = new byte[lengthInBytes];
		double randomLuminance = Math.random(); // Store a random number between 0 and 1				
				
		int index = 0; 
		
		// Iterate over all pixels of the image
		for (float luminance : grayscalePixels) {
			int byteIndex = index / 8;
			
			// Set the bit corresponding to the current pixel or synapse
			if (luminance > randomLuminance) 
				spikeInput[byteIndex] |= (1 << index - byteIndex * 8);		
			
			index++;
		}
		
		return spikeInput;
	}
	
	/**
	 * Compute the rate of firing of one or more spike trains. 
	 * The rate is adimensional and should be divided by the time bin to 
	 * get the dimensional one. 
	 */
	
	public static float[] computeFiringRate(ArrayList<byte[]> spikeTrains, short numOfSynapses) {
		float[] firingRates = new float[numOfSynapses];
		
		// Each element of the ArrayList is the spike input at a different time		
		for (byte[] spikeInput : spikeTrains) { // Iterating over time
			for (int index = 0; index < numOfSynapses; index++) { // Iterating over the synapses of the input
				int byteIndex = index / 8;
				
				// If the current synapse had received a spike, increase the firing rate
				firingRates[index] = ((spikeInput[byteIndex] >> (index - byteIndex * 8)) & 1) == 1 ? 
						firingRates[index] + 1 : firingRates[index];
			}
		}
		
		// To get the firing rate divide the number of times the synapses have received a spike 
		// for the length of the spike train
		for (float firingRate : firingRates)
			firingRate /= spikeTrains.size();
		
		return firingRates;
	}
}
