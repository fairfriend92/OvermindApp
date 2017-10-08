import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class CandidatePicsReceiver extends Thread {
	
	private static String serverIP = null;
	private static boolean shutdown = false;
	
	@Override
	public void run() {
		super.run();
		
		/* Get this server IP */
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
            serverIP = s.next();
        } catch (java.io.IOException e) {
        	e.printStackTrace();
        }        
        assert serverIP != null;        
        System.out.println("This server has IP " + serverIP);	
       
        /* Build the TCP socket */
        ServerSocket serverSocket = null;		
		try {
			serverSocket = new ServerSocket(Constants.SERVER_PORT);
		} catch (IOException e) {
        	e.printStackTrace();
		}		
		assert serverSocket != null;
	
		ObjectInputStream socketInputStream = null;
		
		while(!shutdown) {			
			/* Accept connections from the socket and read the Candidate objects from them */
			com.example.muondetector.Candidate candidate = null;
			try {				
				Socket clientSocket = serverSocket.accept(); // Accept connection
				clientSocket.setTrafficClass(Constants.IPTOS_RELIABILITY);
				socketInputStream = new ObjectInputStream(clientSocket.getInputStream()); // Establish a stream from which the object can be read
				candidate = (com.example.muondetector.Candidate) socketInputStream.readObject(); // Read the candidate object
			} catch (IOException | ClassNotFoundException e) {
	        	e.printStackTrace();
			}			
			assert candidate != null;
			System.out.println(candidate.particleTag);						
		}
		
	}

}
