import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

public class Main {
	
	/* Collections of nodes */
	
	static ArrayList<Node> excNodes = new ArrayList<>(); // Nodes with excitatory neurons only 
	static ArrayList<Node> inhNodes = new ArrayList<>(); // Nodes with inhibitory neurons only 
	
	/* Panels */
	
	private static JPanel logPanel = new JPanel();
	static JPanel mainPanel = new JPanel();
	
	/* Buttons */
	
	private static JButton addNodeToExc = new JButton("Add");
	private static JButton addNodeToInh = new JButton("Add");
	private static JButton removeNodeFromExc = new JButton("Remove");
	private static JButton removeNodeFromInh = new JButton("Remove");
	private static JButton trainNetwork = new JButton("Train");
	private static JButton analyzeSamples = new JButton("Analyze");
	
	/* List models */
	
	static DefaultListModel<String> excNodesListModel = new DefaultListModel<>();
	static DefaultListModel<String> inhNodesListModel = new DefaultListModel<>();
	
	/* Constants */
	
	private static final int NO_ITEM_SELECTED = -1; // Constant used to check against values returned by the JList methods.
	
	/* Custom classes */
	
	private static NetworkTrainer networkTrainer = new NetworkTrainer();

	public static void main(String[] args) {
		MainFrame.main(args); // Start the Overmind server. 
		
		CandidatePicsReceiver candidatePicsReceiver = new CandidatePicsReceiver();
		candidatePicsReceiver.start();
		
		ServerInterfacer serverInterfacer = new ServerInterfacer();
		serverInterfacer.start();
		
		displayMainFrame();
	}
	
	private static void displayMainFrame() {
		JPanel commandsPanel = new JPanel();
		JPanel excNodesPanel = new JPanel();
		JPanel inhNodesPanel = new JPanel();
		JPanel upperPanelsContainer = new JPanel();
		
		JFrame mainFrame = new JFrame();	
				
		JList<String> excNodesList = new JList<>();
		JList<String> inhNodesList = new JList<>();
		
		JScrollPane excNodesScrollPane = new JScrollPane();
		JScrollPane inhNodesScrollPane = new JScrollPane();		
		
		/*
		 * Build the individual panels.
		 */
		
		/* Commands panel */
		
		commandsPanel.setLayout(new GridLayout(2, 1));
		commandsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Commands"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		commandsPanel.add(trainNetwork);
		commandsPanel.add(analyzeSamples);
		
		/* Log panel */
		
		logPanel.add(new JLabel("Log info are shown here."));
		
		/* Excitatory nodes panel */
		
		excNodesPanel.setLayout(new BoxLayout(excNodesPanel, BoxLayout.Y_AXIS));
		excNodesPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Exc. nodes"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		excNodesList.setVisibleRowCount(2);
		
		excNodesScrollPane.setViewportView(excNodesList);
		excNodesScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		excNodesListModel.addElement("No excitatory node");
		excNodesList.setModel(excNodesListModel);
		excNodesList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		excNodesList.setLayoutOrientation(JList.VERTICAL);
		
		excNodesPanel.add(addNodeToExc);
		excNodesPanel.add(removeNodeFromExc);
		excNodesPanel.add(excNodesScrollPane);
		
		/* Inhibitory nodes panel */
		
		inhNodesPanel.setLayout(new BoxLayout(inhNodesPanel, BoxLayout.Y_AXIS));
		inhNodesPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Inh. nodes"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		inhNodesList.setVisibleRowCount(2);
		
		inhNodesScrollPane.setViewportView(inhNodesList);
		inhNodesScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		inhNodesListModel.addElement("No inhibitory node");
		inhNodesList.setModel(inhNodesListModel);
		inhNodesList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		inhNodesList.setLayoutOrientation(JList.VERTICAL);
		
		inhNodesPanel.add(addNodeToInh);
		inhNodesPanel.add(removeNodeFromInh);
		inhNodesPanel.add(inhNodesScrollPane, BorderLayout.CENTER);
		
		/* Upper panels container */
		
		upperPanelsContainer.setLayout(new BoxLayout(upperPanelsContainer, BoxLayout.X_AXIS));
		upperPanelsContainer.add(commandsPanel);
		upperPanelsContainer.add(excNodesPanel);
		upperPanelsContainer.add(inhNodesPanel);
		
		/* Main panel */
				
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.add(upperPanelsContainer);
		mainPanel.add(logPanel);
		
		/*
		 * Define buttons actions. 
		 */
		
		trainNetwork.addActionListener(new ActionListener() { 
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (excNodesListModel.isEmpty() | inhNodesListModel.isEmpty()) {
					updateLogPanel("Select an input and output layer first.", Color.RED);
				} else {
					disablePanel(); // During the learning phase the user shouldn't change the network topology.
					
					boolean noErrorRaised = networkTrainer.checkNetworkTopology();	
					
					if (noErrorRaised) {							
					
						// Sending the weights to the terminals can take a long time so,
						// to not block the GUI thread, this is done on a separate thread. 
						Thread setWeightsThread = new Thread() {
							@Override
							public void run() {
								super.run();
								networkTrainer.setSynapticWeights();
							}
						};		
						
						noErrorRaised = networkTrainer.startTraining();				
					} 
					
					if (!noErrorRaised)
						resetNetwork();												
				}
			}
		});
		
		addNodeToExc.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				Node selectedNode = VirtualLayerVisualizer.selectedNode;
				
				if (selectedNode == null) {
					updateLogPanel("No node selected.", Color.RED); 
				} else if (excNodes.contains(selectedNode)){
					updateLogPanel("Node is already present.", Color.RED); 
				} else if (inhNodes.contains(selectedNode)) {
					updateLogPanel("Selected node is already inhibitory.", Color.RED); 
				} else {
					updateLogPanel("Node added to exc. nodes.", Color.BLACK); 
					
					// Prevent the user from stimulating the terminal.
					selectedNode.terminalFrame.noneRadioButton.doClick();
					selectedNode.terminalFrame.randomSpikesRadioButton.setEnabled(false);
					selectedNode.terminalFrame.refreshSignalRadioButton.setEnabled(false);
					
					if (excNodesListModel.contains("No excitatory node")) // The default message should be cleared if a node is added to the list.
						excNodesListModel.clear();
					
					excNodesListModel.addElement(selectedNode.terminal.ip);
					excNodes.add(selectedNode);
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		removeNodeFromExc.addActionListener(new ActionListener() {			
			@Override
			public void actionPerformed(ActionEvent arg0) {
				int selectionIndex = excNodesList.getSelectedIndex();
				
				if(selectionIndex == NO_ITEM_SELECTED) {
					updateLogPanel("Select an exc. node first.", Color.RED); 
				} else if (excNodesListModel.contains("No excitatory node")) {
					updateLogPanel("No exc. nodes to remove.", Color.RED); 
					
				} else {
					excNodes.get(selectionIndex).isExternallyStimulated = false;
					excNodes.get(selectionIndex).terminalFrame.randomSpikesRadioButton.setEnabled(true);
					excNodes.get(selectionIndex).terminalFrame.refreshSignalRadioButton.setEnabled(true);					
					excNodes.remove(selectionIndex);
					excNodesListModel.remove(selectionIndex);
					
					updateLogPanel("Node removed from exc. nodes.", Color.BLACK);
					
					if (excNodesListModel.isEmpty())
						excNodesListModel.addElement("No excitatory node");
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});		
		
		addNodeToInh.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				Node selectedNode = VirtualLayerVisualizer.selectedNode;
				
				if (selectedNode == null) {
					updateLogPanel("No node selected.", Color.RED); 
				} else if (inhNodes.contains(selectedNode)){
					updateLogPanel("Node is already present.", Color.RED); 
				} else if (excNodes.contains(selectedNode)) {
					updateLogPanel("Selected node is already excitatory.", Color.RED); 
				} else {
					updateLogPanel("Node added to inh. nodes.", Color.BLACK); 
					
					selectedNode.terminalFrame.noneRadioButton.doClick();
					selectedNode.terminalFrame.randomSpikesRadioButton.setEnabled(false);
					selectedNode.terminalFrame.refreshSignalRadioButton.setEnabled(false);
					
					if (inhNodesListModel.contains("No inhibitory node"))
						inhNodesListModel.clear();
					
					inhNodesListModel.addElement(selectedNode.terminal.ip);
					inhNodes.add(selectedNode);
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		removeNodeFromInh.addActionListener(new ActionListener() {			
			@Override
			public void actionPerformed(ActionEvent arg0) {
				int selectionIndex = inhNodesList.getSelectedIndex();
				
				if(selectionIndex == NO_ITEM_SELECTED) {
					updateLogPanel("Select an inh. node first.", Color.RED); 
				} else if (inhNodesListModel.contains("No inhibitory node")) {
					updateLogPanel("No inh. nodes to remove.", Color.RED); 
					
				} else {
					inhNodes.get(selectionIndex).terminalFrame.randomSpikesRadioButton.setEnabled(true);
					inhNodes.get(selectionIndex).terminalFrame.refreshSignalRadioButton.setEnabled(true);
					inhNodes.remove(selectionIndex);
					inhNodesListModel.remove(selectionIndex);
					
					updateLogPanel("Node removed from inh. nodes.", Color.BLACK);
					
					if (inhNodesListModel.isEmpty())
						inhNodesListModel.addElement("No inhibitory node");
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		/*
		 * Create the frame. 
		 */
		
		mainFrame.setTitle("MuonDetectorTeacher");
		mainFrame.setContentPane(mainPanel);
		mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		mainFrame.pack();
		mainFrame.setAlwaysOnTop(true);
		mainFrame.setVisible(true);
	}
	
	/**
	 * Reset the network to the default state before any node was selected. 
	 * This method is used whenever an error arises during either the training or the
	 * testing phase and the application must be interrupted. 
	 */
	
	private static void resetNetwork() {
		// Create a dummy Terminal object with the sole purpose of identifying the 
		// input sender among the presynaptic terminals of the excNode and to remove it. 
		com.example.overmind.Terminal server = new com.example.overmind.Terminal();
		server.ip = CandidatePicsReceiver.serverIP;
		server.natPort = Constants.UDP_PORT;
		
		for (Node excNode : excNodes) {
			excNode.terminal.presynapticTerminals.remove(server);
			excNode.terminalFrame.randomSpikesRadioButton.setEnabled(true);
			excNode.terminalFrame.refreshSignalRadioButton.setEnabled(true);
			excNode.isExternallyStimulated = false;
			
			if (!VirtualLayerManager.availableNodes.contains(excNode))
				VirtualLayerManager.availableNodes.add(excNode);			
		}
		
		for (Node inhNode : inhNodes) {
			inhNode.terminalFrame.randomSpikesRadioButton.setEnabled(true);
			inhNode.terminalFrame.refreshSignalRadioButton.setEnabled(true);
			
			if (!VirtualLayerManager.availableNodes.contains(inhNode))
				VirtualLayerManager.availableNodes.add(inhNode);
		}
		
		excNodes.clear();
		excNodesListModel.clear();
		excNodesListModel.addElement("No excitatory node");
		
		inhNodes.clear();
		inhNodesListModel.clear();
		inhNodesListModel.addElement("No inhibitory node");
		
		enablePanel();
	}
	
	/**
	 * Disable all the commands of the panel.
	 */
	
	private static void disablePanel() {
		addNodeToExc.setEnabled(false);
		addNodeToInh.setEnabled(false);
		removeNodeFromExc.setEnabled(false);
		removeNodeFromInh.setEnabled(false);
		trainNetwork.setEnabled(false);
		analyzeSamples.setEnabled(false);
	}
	
	/**
	 * Re-enable all the command of the panel.
	 */
	
	private static void enablePanel() {
		addNodeToExc.setEnabled(true);
		addNodeToInh.setEnabled(true);
		removeNodeFromExc.setEnabled(true);
		removeNodeFromInh.setEnabled(true);
		trainNetwork.setEnabled(true);
		analyzeSamples.setEnabled(true);
	}
	
	/**
	 * Show text on the log panel.
	 */
	
	static void updateLogPanel(String logText, Color color) {
		logPanel.removeAll();
		JLabel logMessage = new JLabel(logText);
		logMessage.setForeground(color);
		logPanel.add(logMessage);
		logPanel.repaint();
		logPanel.revalidate();
	}

}
