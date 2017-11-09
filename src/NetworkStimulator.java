import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.*;

/**
 * Class that contains methods to send an input to one or more layers of the
 * neural network.
 * @author rodolfo
 *
 */

public class NetworkStimulator {
	
	/**
	 * Send a luminance map as an input to the chosen input layers. 
	 * Launch a separate thread for each layer to send the inputs. Then wait 
	 * for the threads to finish their jobs before returning.  
	 */
	
	public boolean stimulateWithLuminanceMap(int stimulationLength, int deltaTime, Node[] inputLayers, GrayscaleCandidate[] inputs) {	
		long stimulationStartTime = System.nanoTime();
		
		if (inputLayers.length != inputs.length) {
			System.out.println("ERROR: number of inputs is different from number of input layers.");
			return false;
		}
		
		// Create a service for the threads that send the inputs to the respectinve input layers.
		ExecutorService inputSenderService = Executors.newFixedThreadPool(inputs.length);
		
		// List of future objects used to signal when an inputSender thread is done.
		ArrayList<Future<?>> inputSenderFutures = new ArrayList<Future<?>>(inputs.length);
		
		for (int index = 0; index < inputs.length; index++) {
			// The input should be sent to a node only if this one is not being stimulated already.
			if (inputLayers[index].isBeingStimulated()) {
				System.out.println("Input layer with IP: " + inputLayers[index].terminal.ip + " is already being stimulated.");
			} else {
				// For each inputLayer start a thread to stimulate it.
				Future<?> inputSenderFuture = 
						inputSenderService.submit(new InputSender(stimulationLength, deltaTime, inputLayers[index], inputs[index]));
								
				inputSenderFutures.add(inputSenderFuture);
			}
		}
		
		// Wait for all the InputSender threads to finish by retrieving their Future objects.		
		try {
			for (Future<?> inputSenderFuture : inputSenderFutures)
				inputSenderFuture.get();
		} catch (InterruptedException | ExecutionException e) {
			System.out.println(e);
			return false;
		}
		
		System.out.println("Stimulation length: " + (System.nanoTime() - stimulationStartTime) / Constants.NANO_TO_MILLS_FACTOR + " ms.");
		
		return true;
	}
	
	/**	 
	 * The luminance map is first converted in a spike train whose length in units 
	 * of time is determined by the length of the stimulation process and by the size of the bins. 
	 * Then a new sample of the spike train is sent every deltaTime ms. 
	 */
	
	private class InputSender implements Runnable {
		private int numOfIterations; // How many times should the input be sent to the network?
		private Node inputLayer;
		private GrayscaleCandidate input;
		private int deltaTime;
		
		InputSender(int stimulationLength, int deltaTime, Node inputLayer, GrayscaleCandidate input) {
			this.deltaTime = deltaTime;
			numOfIterations = stimulationLength / deltaTime;
			this.inputLayer = inputLayer;
			this.input = input;
		}
		
		@Override
		public void run() {			
			inputLayer.isExternallyStimulated = true;
			
			// Socket used to send the input to the node. 
			DatagramSocket outputSocket = null;
	        try {
	    	    outputSocket = new DatagramSocket();
	    	    outputSocket.setTrafficClass(Constants.IPTOS_THROUGHPUT);   
	        } catch (SocketException e) {
	        	e.printStackTrace();
	        }
	        
	        // Address and nat port of the terminal to which the input should be sent.
	        InetAddress inetAddress = null;
			try {
				inetAddress = InetAddress.getByName(inputLayer.terminal.ip);
			} catch (UnknownHostException e) {
				System.out.println(e);
			}
			assert inetAddress != null;
	        int natPort = inputLayer.terminal.natPort;
			
	        // Send a new input, Poisson distributed, to the node every deltaTime
	        // for a total of numOfIterations times. 
			for (int index = 0; index < numOfIterations; index++) {
				long startingTime = System.nanoTime();				
				
				byte[] spikeInput = SpikeInputCreator.createFromLuminance(input.grayscalePixels);
				
				// Add the generated spikes array to the presynaptic spike trains buffer of the input layer. 
				NetworkTrainer.SpikeTrainsBuffers spikeTrainsBuffers = NetworkTrainer.spikeTrainsBuffersMap.get(inputLayer.physicalID);
				spikeTrainsBuffers.presynapticSpikeTrains.add(spikeInput);
				
				// Save the number of pixels that make up the input.
				spikeTrainsBuffers.numOfSpikeTrains = (short)input.grayscalePixels.length;
				
				try {
					DatagramPacket spikeInputPacket = new DatagramPacket(spikeInput, spikeInput.length, inetAddress, natPort);
					outputSocket.send(spikeInputPacket);
				} catch (IOException e) {
					System.out.println(e);
				}		
				
				while((System.nanoTime() - startingTime) / Constants.NANO_TO_MILLS_FACTOR < deltaTime) {
					// TODO: other ways other than a busy loop?
				}
			}
			
			inputLayer.isExternallyStimulated = false;		
			outputSocket.close();
		}
	}

}
