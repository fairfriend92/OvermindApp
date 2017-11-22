import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import javax.imageio.ImageIO;
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
	
	/* Threading objects */
	
	private static Thread networkTrainerThread;

	public static void main(String[] args) {
		MainFrame.main(args); // Start the Overmind server. 
		
		CandidatePicsReceiver candidatePicsReceiver = new CandidatePicsReceiver();
		candidatePicsReceiver.start();
		
		ServerInterfacer serverInterfacer = new ServerInterfacer();
		serverInterfacer.start();
		
		displayMainFrame();
		
		populatePicsFolders();
		
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
	        public void run() {
	        	candidatePicsReceiver.shutdown = true;
	        	serverInterfacer.shutdown = true;
	        	
	        	try {
					candidatePicsReceiver.serverSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
	        	serverInterfacer.interrupt();
	        	
	        	try {
					candidatePicsReceiver.join();
					serverInterfacer.join();
					if (networkTrainerThread != null)
						networkTrainerThread.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
	        	
	            System.out.println("MuonTeacher: Orderly shutdown succesfull");
	        }
	    }, "Shutdown-thread"));
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
				if (excNodes.isEmpty() | inhNodes.isEmpty()) { 
					updateLogPanel("Select an input and output layer first.", Color.RED);
				} else {
					updateLogPanel("Training started", Color.BLACK);
					disablePanel(); // During the learning phase the user shouldn't change the network topology.	
					
					networkTrainerThread = new Thread() {
						@Override
						public void run () {
							super.run();
							boolean operationSuccessful = true; 
							
							operationSuccessful &= networkTrainer.checkTopology();							
							if (operationSuccessful)
								operationSuccessful &= networkTrainer.setSynapticWeights(); 
							if (operationSuccessful)
								operationSuccessful &= networkTrainer.startTraining();
							
							if (!operationSuccessful) {
								resetNetwork();
								enablePanel();
							} else {								
								enablePanel();
								updateLogPanel("Training completed", Color.BLACK);
							}
						}
					};
					networkTrainerThread.start();						
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
					selectedNode.isExternallyStimulated = true;
					
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
					selectedNode.isExternallyStimulated = true;
					
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
	
	private static boolean populatePicsFolders() {
		
		/*
		 * Get all the bitmaps that need to be converted into luminance 
		 * maps.
		 */
		
		String path = new File("").getAbsolutePath();
		path = path.concat("/resources/pics/database");
		File databasedDir = new File(path);
		File[] databasePics = databasedDir.listFiles();
		
		if (databasePics.length == 0 | databasePics == null) {
			updateLogPanel("No pics in the database", Color.RED);
			return false;
		}		
		
		/*
		 * Extract the RGB data from the bitmap and send it to 
		 * CandidatePicsReceiver to be converted into a luminance map. 
		 */
        
        for (File pic : databasePics) {
        	// Read the bitmap.
        	BufferedImage bitmap = null;
        	try {
        		bitmap = ImageIO.read(pic.getAbsoluteFile());
        	} catch (IOException e) {
        		System.out.println(e);
        	} 
        	assert bitmap != null;
        	
        	// Extract the RGB data. 
        	int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        	pixels = bitmap.getRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());
        	
        	int tag = 0; // The tag to be associated with the luminance map.
        	String fileName = pic.getName(); // The name of the file containing the tag. 
        	boolean fileNameIsValid = true; // Flag used to signal if the file name does not contain any tag.
        	
        	// Retreive the tag to associate with the map in the GrayscaleCandidate object from the file name.
        	if (fileName.contains("undetermined"))
        		tag = Constants.UNDETERMINED;
        	else if (fileName.contains("track"))
        		tag = Constants.TRACK;
        	else {
        		fileNameIsValid = false;
        		System.out.println("" + fileName + " is not a valid file name.");
        	}
        	
        	// If the tag could be determined, run the Runnable that computes the luminance map. 
        	if (fileNameIsValid) {
        		CandidatePicsReceiver.ConvertGrayscale convertGrayscale = new CandidatePicsReceiver.ConvertGrayscale(pixels, tag);
        		convertGrayscale.run();
        		pic.delete();
        	}
        	
        }        
        
        updateLogPanel("" + databasePics.length + " pics converted", Color.BLACK);
		
        return true;
	}
	
	/**
	 * Reset the network to the default state before any node was selected. 
	 * This method is used whenever an error arises during either the training or the
	 * testing phase and the application must be interrupted. 
	 */
	
	private static void resetNetwork() {		
		for (Node excNode : excNodes) {
			removeThisAppFromConnections(excNode.terminal);
			
			excNode.terminalFrame.randomSpikesRadioButton.setEnabled(true);
			excNode.terminalFrame.refreshSignalRadioButton.setEnabled(true);
			excNode.isExternallyStimulated = false; // TODO: This field should be unnecessary. 
			
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
	 * Method used to remove any reference of this app from the pre- and postsynaptic connections
	 * of a given terminal, since the equals method of the Terminal object cannot differentiate between
	 * this app and the Overmind server terminal. 
	 * @param terminal
	 */
	
	static void removeThisAppFromConnections(com.example.overmind.Terminal terminal) {
		Iterator<com.example.overmind.Terminal> iterator = terminal.presynapticTerminals.iterator();
		// TODO: Update the weights in the weightsTable of VLM accordingly. 
		
		while (iterator.hasNext()) {
			com.example.overmind.Terminal presynapticTerminal = (com.example.overmind.Terminal) iterator.next();
			if (presynapticTerminal.natPort == Constants.APP_UDP_PORT & 
					presynapticTerminal.ip.equals(terminal.serverIP)) {
				iterator.remove();
				terminal.numOfDendrites += Constants.MAX_PIC_PIXELS;
			}
		}
			
		iterator = terminal.postsynapticTerminals.iterator();
		while (iterator.hasNext()) {
			com.example.overmind.Terminal postsynapticTerminal = (com.example.overmind.Terminal) iterator.next();
			if (postsynapticTerminal.natPort == Constants.APP_UDP_PORT &
					postsynapticTerminal.ip.equals(terminal.serverIP)) {
				iterator.remove();
			}
		}			
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
		
		mainPanel.repaint();
		mainPanel.revalidate();
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
		
		mainPanel.repaint();
		mainPanel.revalidate();
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
