import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.*;

/**
 * Class that contains methods to send an input to one or more layers of the
 * neural network.
 * @author rodolfo
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
			// For each inputLayer start a thread to stimulate it.
			Future<?> inputSenderFuture = 
					inputSenderService.submit(new InputSender(stimulationLength, deltaTime, inputLayers[index], inputs[index]));
							
			inputSenderFutures.add(inputSenderFuture);
		}
		
		// Wait for all the InputSender threads to finish by retrieving their Future objects.		
		try {
			for (Future<?> inputSenderFuture : inputSenderFutures)
				inputSenderFuture.get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			return false;
		}
		
		System.out.println("Stimulation length: " + (System.nanoTime() - stimulationStartTime) / MuonTeacherConst.NANO_TO_MILLS_FACTOR + " ms");
		
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
		private SpikeInputCreator spikeInputCreator = new SpikeInputCreator();
		
		InputSender(int stimulationLength, int deltaTime, Node inputLayer, GrayscaleCandidate input) {
			this.deltaTime = deltaTime;
			numOfIterations = stimulationLength / deltaTime;
			this.inputLayer = inputLayer;
			this.input = input;
		}
		
		@Override
		public void run() {						
			// Socket used to send the input to the node. 
			DatagramSocket outputSocket = null;
	        try {
	    	    outputSocket = new DatagramSocket();
	    	    outputSocket.setTrafficClass(MuonTeacherConst.IPTOS_THROUGHPUT);   
	        } catch (SocketException e) {
	        	e.printStackTrace();
	        }
	        
	        // Address and nat port of the terminal to which the input should be sent.
	        InetAddress inetAddress = null;
			try {
				inetAddress = InetAddress.getByName(inputLayer.terminal.ip);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			assert inetAddress != null;
	        int natPort = inputLayer.terminal.natPort;
			
	        // Send a new input, Poisson distributed, to the node every deltaTime
	        // for a total of numOfIterations times. 
			for (int index = 0; index < numOfIterations; index++) {
				long startingTime = System.nanoTime();				
				
				byte[] spikeInput = spikeInputCreator.createFromLuminance(input.grayscalePixels);
				
				try {
					DatagramPacket spikeInputPacket = new DatagramPacket(spikeInput, spikeInput.length, inetAddress, natPort);
					outputSocket.send(spikeInputPacket);
				} catch (IOException e) {
					e.printStackTrace();
				}		
				
				while((System.nanoTime() - startingTime) / MuonTeacherConst.NANO_TO_MILLS_FACTOR < deltaTime) {
					// TODO: Use sleep instead and handle eventual interruptions.
				}
			}
			
			outputSocket.close();
		}
	}

}
