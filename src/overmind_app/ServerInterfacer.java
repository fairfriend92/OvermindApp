package overmind_app;
import overmind_server.*;

public class ServerInterfacer extends Thread {
	
	private final int MAX_REMOVED_NODES = 8; // Max number of removed nodes that this application can handle at any time.
	
	boolean shutdown = false;
	
	@Override
	public void run() {
		super.run();
		
		ApplicationInterface.RegisteredApp thisApplication = ApplicationInterface.registerApplication(MAX_REMOVED_NODES, "MuonDetectorTeacher");
		
		while(!shutdown) {		
			ApplicationInterface.RemovedNode removedNodeObject = null;
			
			try {
				removedNodeObject = thisApplication.removedNodes.take();
			} catch (InterruptedException e) {
				System.out.println("MuonTeacher: serverInterfacer interrupted");
				break;
			}
			assert removedNodeObject != null;			
			
			System.out.println("A node has been removed from the network");
			
			// Enter the block if no substitute for the removed node is available. 			
			if (removedNodeObject.shadowNode == null) {	
				
				/*
				 * Update the lists of excitatory  and inhibitory nodes. 
				 */
				
				if (Main.excNodes.contains(removedNodeObject.removedNode)) {
					Main.excNodes.remove(removedNodeObject.removedNode);
					Main.excNodesListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}			
				
				if (Main.inhNodes.contains(removedNodeObject.removedNode)) {
					Main.inhNodes.remove(removedNodeObject.removedNode);
					Main.inhNodesListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}		
			}
			
			// If the exc. nodes and inh. nodes list are empty, put back the default text in them. 			
			if (Main.excNodesListModel.isEmpty())
				Main.excNodesListModel.addElement("No excitatory node");			
			if (Main.inhNodesListModel.isEmpty())
				Main.inhNodesListModel.addElement("No inhibitory node");	
			
			Main.mainPanel.revalidate();
			Main.mainPanel.repaint();
		}
				
	}

}
