
public class ServerInterfacer extends Thread {
	
	private final int MAX_REMOVED_NODES = 8; // Max number of removed nodes that this application can handle at any time.
	
	private static boolean shutdown = false;
	
	@Override
	public void run() {
		super.run();
		
		ApplicationInterface.RegisteredApp thisApplication = ApplicationInterface.registerApplication(MAX_REMOVED_NODES);
		
		while(!shutdown) {			
			try {
				thisApplication.removedNodes.take();
			} catch (InterruptedException e) {
				System.out.println(e);
			}
			
			System.out.println("A node has been removed from the network");
			// TODO: Handle the removal of nodes. 						
		}
				
	}

}
