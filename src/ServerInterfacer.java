
public class ServerInterfacer extends Thread {
	
	private final int MAX_REMOVED_NODES = 8; // Max number of removed nodes that this application can handle at any time.
	
	private static boolean shutdown = false;
	
	@Override
	public void run() {
		super.run();
		
		ApplicationInterface.RegisteredApp thisApplication = ApplicationInterface.registerApplication(MAX_REMOVED_NODES, "MuonDetectorTeacher");
		
		while(!shutdown) {		
			ApplicationInterface.RemovedNode removedNodeObject = null;
			
			try {
				removedNodeObject = thisApplication.removedNodes.take();
			} catch (InterruptedException e) {
				System.out.println(e);
			}
			assert removedNodeObject != null;			
			
			System.out.println("A node has been removed from the network");
			
			// Enter the block if no substitute for the removed node is available. 			
			if (removedNodeObject.shadowNode == null) {	
				
				/*
				 * Update the lists of input and output layers if the removed node 
				 * belonged to either of them. 
				 */
				
				if (Main.inputLayers.contains(removedNodeObject.removedNode)) {
					Main.inputLayers.remove(removedNodeObject.removedNode);
					Main.inputLayersListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}			
				
				if (Main.outputLayers.contains(removedNodeObject.removedNode)) {
					Main.outputLayers.remove(removedNodeObject.removedNode);
					Main.outputLayersListModel.removeElement(removedNodeObject.removedNode.terminal.ip);					
				}		
			}
			
			// If the input and output layers are now empty, put back the default text in them. 			
			if (Main.inputLayersListModel.isEmpty())
				Main.inputLayersListModel.addElement("No input layer");			
			if (Main.outputLayersListModel.isEmpty())
				Main.outputLayersListModel.addElement("No output layer");	
			
			Main.mainPanel.revalidate();
			Main.mainPanel.repaint();
		}
				
	}

}
