package overmind_app;
import java.util.ArrayList;

/**
 * Class containing methods that pertain to the input made of spikes which is sent
 * to the network. 
 * @author rodolfo
 *
 */

public class SpikeInputCreator {
	float MAX_LUMINANCE = 63.75f;		
	
	private int[] waitARP = new int[MuonTeacherConst.MAX_PIC_PIXELS]; // Array holding counter indexes that account for the absolute refractory period.
	
	/**
	 * Create a spike input from a map of pixel luminance
	 */
	
	// TODO: Implement refractoriness?
	
	public  byte[] createFromLuminance(float[] grayscalePixels, boolean printValues) {
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
			
			/*
			if (printValues & Main.isTraining)
				System.out.println("" + luminance);
			*/
			
			// Cut-off luminance to prevent neurons from firing continuously. 
			//luminance *= MAX_LUMINANCE / 100.0f;
			
			// Set the bit corresponding to the current pixel or synapse
			if (randomLuminance < luminance) {		
				spikeInput[byteIndex] |= (1 << index - byteIndex * 8);
				/*
				if (waitARP[index] == 0) { // A spike can be emitted only after the absolute refractory period has elapsed.
					spikeInput[byteIndex] |= (1 << index - byteIndex * 8);
        			waitARP[index] = (int) (Constants.ABSOLUTE_REFRACTORY_PERIOD / Constants.SAMPLING_RATE);  
				} else if (waitARP[index] > 0) {
					waitARP[index]--;
					spikeInput[byteIndex] &= ~(1 << index - byteIndex * 8);
				} else {
					spikeInput[byteIndex] &= ~(1 << index - byteIndex * 8);
				}		
				*/				
			}
			
			index++;			
		}		
		
		return spikeInput;
	}
}
