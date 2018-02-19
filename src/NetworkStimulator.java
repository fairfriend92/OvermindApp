import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
	
	private byte[] noiseInput = new byte[MuonTeacherConst.MAX_PIC_PIXELS];
	private SpikeInputCreator spikeInputCreator = new SpikeInputCreator();
	
	/*
	 * When the class is instantiated create an input array from a picture containing
	 * only noise and no trace. 
	 */
	
	NetworkStimulator() {
		String path = new File("").getAbsolutePath();
		path = path.concat("/resources/pics/tagged/noise");
		File noiseDir = new File(path);
		ArrayList<File> noiseFiles = new ArrayList<>(Arrays.asList(noiseDir.listFiles()));
		
		// Delete unwanted files that may have been included. 
		Iterator<File> iterator = noiseFiles.iterator();
		while (iterator.hasNext()) {
			File file = iterator.next();
			if (file.getName().equals(".gitignore"))
				iterator.remove();
		}
				
		if (noiseFiles.size() == 0 | noiseFiles == null) {
			Main.updateLogPanel("Noise sample not found", Color.RED);			
		} else {		
			System.out.println("test");
			
			// Any of the noise picture is fine, so take the first one in the array. 
			try {
				FileInputStream fileInputStream = new FileInputStream(noiseFiles.get(0));
				ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream); 
				GrayscaleCandidate noiseCandidate = (GrayscaleCandidate) objectInputStream.readObject();
				noiseInput = spikeInputCreator.createFromLuminance(noiseCandidate.grayscalePixels);
			} catch (IOException | ClassNotFoundException e) {
				e.printStackTrace();
			} 
		}
	}
	
	// Hash map used to store the sockets which send the inputs to the terminals.
	ConcurrentHashMap<Integer, DatagramSocket> socketsHashMap = null;
	
	// Create a service for the threads that send the inputs to the respectinve input layers.
	ExecutorService inputSenderService = null;
		
	/**
	 * Send a luminance map as an input to the chosen input layers. 
	 * Launch a separate thread for each layer to send the inputs. Then wait 
	 * for the threads to finish their jobs before returning.  
	 */
	
	public ArrayList<Future<?>> stimulateWithLuminanceMap(float stimulationLength, float pauseLength, float deltaTime, Node[] inputLayers, GrayscaleCandidate[] inputs) {			
		if (inputLayers.length != inputs.length) {
			System.out.println("ERROR: number of inputs is different from number of input layers.");
			return null;
		}
		
		if (socketsHashMap == null | inputSenderService == null) {
			socketsHashMap = new ConcurrentHashMap<>(inputLayers.length);
			inputSenderService = Executors.newFixedThreadPool(inputs.length);
		}		
				
		// List of future objects used to signal when an inputSender thread is done.
		ArrayList<Future<?>> inputSenderFutures = new ArrayList<Future<?>>(inputs.length);
		
		for (int index = 0; index < inputs.length; index++) {
			// For each inputLayer start a thread to stimulate it.
			Future<?> inputSenderFuture = 
					inputSenderService.submit(new InputSender(stimulationLength, pauseLength, deltaTime, inputLayers[index], inputs[index]));
							
			inputSenderFutures.add(inputSenderFuture);
		}			
		
		return inputSenderFutures;
	}
	
	/**	 
	 * The luminance map is first converted in a spike train whose length in units 
	 * of time is determined by the length of the stimulation process and by the size of the bins. 
	 * Then a new sample of the spike train is sent every deltaTime ms. 
	 */
	
	private class InputSender implements Runnable {
		private int stimulationIterations, // How many times should the input be sent to the network?
		pauseIterations; // How many times should the dummy input be sent?
		private Node inputLayer;
		private GrayscaleCandidate input;
		private float deltaTime;
		
		InputSender(float stimulationLength, float pauseLength, float deltaTime, Node inputLayer, GrayscaleCandidate input) {
			this.deltaTime = deltaTime;
			stimulationIterations = (int)(stimulationLength / deltaTime);
			pauseIterations = (int)(pauseLength / deltaTime);
			this.inputLayer = inputLayer;
			this.input = input;
		}
		
		@Override
		public void run() {						
			// Socket used to send the input to the node. 
			DatagramSocket outputSocket = socketsHashMap.get(inputLayer.physicalID);
		
			// If necessary create the socket and put it in the hash map.
			if (outputSocket == null) {
		        try {
		    	    outputSocket = new DatagramSocket();
		    	    outputSocket.setTrafficClass(MuonTeacherConst.IPTOS_THROUGHPUT);   
		        } catch (SocketException e) {
		        	e.printStackTrace();
		        }
		        socketsHashMap.put(inputLayer.physicalID, outputSocket);
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
			for (int index = 0; index < stimulationIterations + pauseIterations; index++) {
				long startingTime = System.nanoTime();				
				
				byte[] spikeInput = index < stimulationIterations ? 
						spikeInputCreator.createFromLuminance(input.grayscalePixels) : 
							noiseInput;
				
				try {
					DatagramPacket spikeInputPacket = new DatagramPacket(spikeInput, spikeInput.length, inetAddress, natPort);
					outputSocket.send(spikeInputPacket);
				} catch (IOException e) {
					e.printStackTrace();
				}		
				
				while((System.nanoTime() - startingTime) / MuonTeacherConst.MILLS_TO_NANO_FACTOR < deltaTime) {
					// TODO: Use sleep instead and handle eventual interruptions.
				}
			}			
		}
	}

}
