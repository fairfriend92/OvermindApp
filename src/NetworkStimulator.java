import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkStimulator {
	
	public boolean stimulateWithLuminanceMap(int stimulationLength, int deltaTime, Node[] inputLayers, GrayscaleCandidate[] inputs) {		
		if (inputLayers.length != inputs.length) {
			System.out.println("ERROR: number of inputs is different from number of input layers");
			return false;
		}
		
		// Create a service for the threads that send the inputs to the respectinve input layers
		ExecutorService inputSenderService = Executors.newFixedThreadPool(inputs.length);
		
		for (Node inputLayer : inputLayers) {
			// The input should be sent to a node only if this one is not being stimulated already.
			if (inputLayer.isBeingStimulated()) {
				System.out.println("Input layer with IP: " + inputLayer.terminal.ip + " is already being stimulated");
			} else {
				
			}
		}
		
		return true;
	}
	
	private class inputSender implements Runnable {
		private int numOfIterations; // How many times should the input be sent to the network?
		private Node inputLayer;
		private GrayscaleCandidate input;
		private int deltaTime;
		
		inputSender(int stimulationLength, int deltaTime, Node inputLayer, GrayscaleCandidate input) {
			this.deltaTime = deltaTime;
			numOfIterations = stimulationLength / deltaTime;
			this.inputLayer = inputLayer;
			this.input = input;
		}
		
		@Override
		public void run() {
			long lastTime = 0; // Time at which the input was sent the last time.
			
			inputLayer.isExternallyStimulated = true;
			
			// Socket used to send the input to the node 
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
				while((System.nanoTime() - lastTime) / Constants.NANO_TO_MILLS_FACTOR < deltaTime) // TODO: other ways other than a busy loop?
				
				lastTime = System.nanoTime();
				byte[] spikeInput = SpikeInputCreator.createFromLuminance(input.grayscalePixels);
				
				try {
					DatagramPacket spikeInputPacket = new DatagramPacket(spikeInput, spikeInput.length, inetAddress, natPort);
					outputSocket.send(spikeInputPacket);
				} catch (IOException e) {
					System.out.println(e);
				}
			}
			
		}
	}

}
