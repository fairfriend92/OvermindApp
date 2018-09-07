package overmind_app;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CandidatePicsReceiver extends Thread {
	
	boolean shutdown = false;
	static ExecutorService cachedThreadPoolExecutor = Executors.newCachedThreadPool(); 
	ServerSocket serverSocket = null;
	
	@Override
	public void run() {
		super.run();
          
        /* Build the TCP socket */       		
		try {
			serverSocket = new ServerSocket(MuonTeacherConst.MUON_DETECTOR_SERVER_PORT);
		} catch (IOException e) {
        	e.printStackTrace();
		}		
		assert serverSocket != null;
	
		ObjectInputStream socketInputStream = null;
		
		Socket clientSocket = null;
		
		while(!shutdown) {			
			/* Accept connections from the socket and read the Candidate objects from them */
			try {				
				clientSocket = serverSocket.accept(); // Accept connection. Blocking operation.
				clientSocket.setTrafficClass(MuonTeacherConst.IPTOS_RELIABILITY);
				socketInputStream = new ObjectInputStream(clientSocket.getInputStream()); // Establish a stream from which the object can be read
				cachedThreadPoolExecutor.execute(new TerminalListener(socketInputStream)); // Create a thread which listens for incoming pics from the connected terminal
			} catch (IOException e) {
	        	System.out.println("MuonTeacher: serverSocket closed");
			}				
		}		
		
		try {
			if (clientSocket != null)
				clientSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Listen for pics sent by the connected terminal through the stream passed by the constructor
	 */
	
	private class TerminalListener implements Runnable {
		private ObjectInputStream socketInputStream;
		
		public TerminalListener (ObjectInputStream socketInputStream) {
			this.socketInputStream = socketInputStream;
		}

		@Override
		public void run() {	
			boolean streamIsUp = true;
			while (streamIsUp) { // Keep listening for new pics until the terminal signals that is sending the last one
				try {
					com.example.muondetector.Candidate candidate = (com.example.muondetector.Candidate) socketInputStream.readObject();
					cachedThreadPoolExecutor.execute(new ConvertGrayscale(candidate.bmpPixels, candidate.particleTag, "")); // Create a thread that transform the pics 
																														// into a grayscale luminance map
				} catch (EOFException e) { // If terminal has closed the stream, exit the runnable 
					streamIsUp = false;
				} catch (ClassNotFoundException | IOException e) {
					e.printStackTrace();
				} 
			}
		}		
	}
	
	/**
	 * Create grayscale luminance maps from the pics sent by the smartphones. Then save the map and the
	 * accompanying tag in the local storage.
	 */
	
	static class ConvertGrayscale implements Runnable {
		private int[] pixels;
		private float[] grayscalePixels;
		private int tag;
		private String fileName;
		
		public ConvertGrayscale (int[] pixels, int tag, String fileName) {
			this.pixels = pixels;
			this.tag = tag;
			this.fileName = fileName;
			grayscalePixels = new float[pixels.length];
		}

		@Override
		public void run() {
			// Calculate the luminance map
			for (int i = 0; i < pixels.length; i++) {
				int red = (pixels[i] >> 16) & 0xff;
				int green = (pixels[i] >> 8) & 0xff;
				int blue = (pixels[i]) & 0xff;			
				
				if (green == 0) 
					grayscalePixels[i] = (float)red / 255 * 0.44f;
				else if (blue == 0)
					grayscalePixels[i] = 0.44f + (float)green / 255 * 0.44f;
				else
					grayscalePixels[i] = 0.88f + (float)blue / 255 * 0.12f;	
				
			}		
			
			// Save the map and the tag
			String absolutePath = new File("").getAbsolutePath();
			
			/* Save a different directory path in the string depending on the particle tag */
			String tagDirectoryPath = ""; 
			switch (tag) {
				case MuonTeacherConst.UNDETERMINED:
					tagDirectoryPath = "/resources/pics/untagged";
					break;
				case MuonTeacherConst.TRACK:
					tagDirectoryPath = "/resources/pics/tagged/track";
					break;
				case MuonTeacherConst.SPOT:
					tagDirectoryPath = "/resources/pics/tagged/spot";
					break;
				case MuonTeacherConst.NOISE:
					tagDirectoryPath = "/resources/pics/tagged/noise";
					break;
			}
			
			// Get the directory where to save the pic 
			String picsPath = absolutePath.concat(tagDirectoryPath);
			File picsDirectory = new File(picsPath);
			
			// Write in a new file the object containing the grayscale map of the luminance and the particle tag
			try {
				File picFile = File.createTempFile("" + pixels.hashCode(), ".gcnd", picsDirectory); // Create a new file
				FileOutputStream fileOutputStream = new FileOutputStream(picFile); // Get a stream to write into the file
				ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream); // Get a stream to write an object
				objectOutputStream.writeObject(new GrayscaleCandidate(grayscalePixels, tag)); // Write the object
				
				// Close the streams
				fileOutputStream.close();
				objectOutputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}							
		}		
	}
}
