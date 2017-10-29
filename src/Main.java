import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;

public class Main {
	
	ArrayList<Node> inputLayers = new ArrayList<>();

	public static void main(String[] args) {
		CandidatePicsReceiver candidatePicsReceiver = new CandidatePicsReceiver();
		candidatePicsReceiver.start();
		
		ServerInterfacer serverInterfacer = new ServerInterfacer();
		serverInterfacer.getState();
	}
	
	private void displayMainFrame() {
		JPanel mainPanel = new JPanel();
		JFrame mainFrame = new JFrame();
		JButton addNodeToInput = new JButton("Add node to input");
				
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.X_AXIS));
		
		addNodeToInput.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (VirtualLayerVisualizer.selectedNode == null) {
					System.out.println("No node selected."); // TODO: Show message on the GUI.
				} else {
					inputLayers.add(VirtualLayerVisualizer.selectedNode);
				}
			}
			
		});
		
		mainFrame.setTitle("MuonDetectorTeacher");
		mainFrame.setContentPane(mainPanel);
		mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		mainFrame.pack();
		mainFrame.setAlwaysOnTop(true);
		mainFrame.setVisible(true);
	}

}
