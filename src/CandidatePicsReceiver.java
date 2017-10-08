import java.io.IOException;
import java.net.ServerSocket;

public class CandidatePicsReceiver extends Thread {
	
	static public String serverIP = null;
	
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
		
	}

}
