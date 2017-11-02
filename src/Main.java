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
	
	static ArrayList<Node> inputLayers = new ArrayList<>();
	static ArrayList<Node> outputLayers = new ArrayList<>();
	
	/* Panels */
	
	private static JPanel logPanel = new JPanel();
	static JPanel mainPanel = new JPanel();
	
	/* List models */
	
	static DefaultListModel<String> inputLayersListModel = new DefaultListModel<>();
	static DefaultListModel<String> outputLayersListModel = new DefaultListModel<>();
	
	/* Constants */
	
	private static final int NO_ITEM_SELECTED = -1; // Constant used to check against values returned by the JList methods.

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
		JPanel inputLayersPanel = new JPanel();
		JPanel outputLayersPanel = new JPanel();
		JPanel upperPanelsContainer = new JPanel();
		
		JFrame mainFrame = new JFrame();
		
		JButton addNodeToInput = new JButton("Add");
		JButton addNodeToOutput = new JButton("Add");
		JButton removeNodeFromInput = new JButton("Remove");
		JButton removeNodeFromOutput = new JButton("Remove");
		
		JList<String> inputLayersList = new JList<>();
		JList<String> outputLayersList = new JList<>();
		
		JScrollPane inputLayersScrollPane = new JScrollPane();
		JScrollPane outputLayersScrollPane = new JScrollPane();		
		
		/*
		 * Build the individual panels.
		 */
		
		/* Commands panel */
		
		commandsPanel.setLayout(new GridLayout(2, 2));
		
		/* Log panel */
		
		logPanel.add(new JLabel("Log info are shown here."));
		
		/* Input layers panel */
		
		inputLayersPanel.setLayout(new BoxLayout(inputLayersPanel, BoxLayout.Y_AXIS));
		inputLayersPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Input layers"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		inputLayersList.setVisibleRowCount(2);
		
		inputLayersScrollPane.setViewportView(inputLayersList);
		inputLayersScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		inputLayersListModel.addElement("No input layer");
		inputLayersList.setModel(inputLayersListModel);
		inputLayersList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		inputLayersList.setLayoutOrientation(JList.VERTICAL);
		
		inputLayersPanel.add(addNodeToInput);
		inputLayersPanel.add(removeNodeFromInput);
		inputLayersPanel.add(inputLayersScrollPane);
		
		/* Output layers panel */
		
		outputLayersPanel.setLayout(new BoxLayout(outputLayersPanel, BoxLayout.Y_AXIS));
		outputLayersPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("Output layers"),
				BorderFactory.createEmptyBorder(5,5,5,5)));
		
		outputLayersList.setVisibleRowCount(2);
		
		outputLayersScrollPane.setViewportView(outputLayersList);
		outputLayersScrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		outputLayersListModel.addElement("No output layer");
		outputLayersList.setModel(outputLayersListModel);
		outputLayersList.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
		outputLayersList.setLayoutOrientation(JList.VERTICAL);
		
		outputLayersPanel.add(addNodeToOutput);
		outputLayersPanel.add(removeNodeFromOutput);
		outputLayersPanel.add(outputLayersScrollPane, BorderLayout.CENTER);
		
		/* Upper panels container */
		
		upperPanelsContainer.setLayout(new BoxLayout(upperPanelsContainer, BoxLayout.X_AXIS));
		upperPanelsContainer.add(commandsPanel);
		upperPanelsContainer.add(inputLayersPanel);
		upperPanelsContainer.add(outputLayersPanel);
		
		/* Main panel */
				
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.add(upperPanelsContainer);
		mainPanel.add(logPanel);
		
		/*
		 * Define buttons actions. 
		 */
		
		addNodeToInput.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				Node selectedNode = VirtualLayerVisualizer.selectedNode;
				
				if (selectedNode == null) {
					updateLogPanel("No node selected.", Color.RED); 
				} else if (inputLayers.contains(selectedNode)){
					updateLogPanel("Node is already present.", Color.RED); 
				} else {
					updateLogPanel("Node added to inputs.", Color.BLACK); 
					
					if (inputLayersListModel.contains("No input layer")) // The default message should be cleared if a node is added to the list.
						inputLayersListModel.clear();
					
					inputLayersListModel.addElement(selectedNode.terminal.ip);
					inputLayers.add(selectedNode);
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		removeNodeFromInput.addActionListener(new ActionListener() {			
			@Override
			public void actionPerformed(ActionEvent arg0) {
				int selectionIndex = inputLayersList.getSelectedIndex();
				
				if(selectionIndex == NO_ITEM_SELECTED) {
					updateLogPanel("Select an input layer first.", Color.RED); 
				} else if (inputLayersListModel.contains("No input layer")) {
					updateLogPanel("No input layers to remove.", Color.RED); 
					
				} else {
					inputLayers.remove(selectionIndex);
					inputLayersListModel.remove(selectionIndex);
					
					updateLogPanel("Node removed from inputs.", Color.BLACK);
					
					if (inputLayersListModel.isEmpty())
						inputLayersListModel.addElement("No input layer");
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});		
		
		addNodeToOutput.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				Node selectedNode = VirtualLayerVisualizer.selectedNode;
				
				if (selectedNode == null) {
					updateLogPanel("No node selected.", Color.RED); 
				} else if (outputLayers.contains(selectedNode)){
					updateLogPanel("Node is already present.", Color.RED); 
				} else {
					updateLogPanel("Node added to outputs.", Color.BLACK); 
					
					if (outputLayersListModel.contains("No output layer"))
						outputLayersListModel.clear();
					
					outputLayersListModel.addElement(selectedNode.terminal.ip);
					outputLayers.add(selectedNode);
					
					mainPanel.revalidate();
					mainPanel.repaint();
				}
			}			
		});
		
		removeNodeFromOutput.addActionListener(new ActionListener() {			
			@Override
			public void actionPerformed(ActionEvent arg0) {
				int selectionIndex = outputLayersList.getSelectedIndex();
				
				if(selectionIndex == NO_ITEM_SELECTED) {
					updateLogPanel("Select an output layer first.", Color.RED); 
				} else if (outputLayersListModel.contains("No output layer")) {
					updateLogPanel("No output layers to remove.", Color.RED); 
					
				} else {
					outputLayers.remove(selectionIndex);
					outputLayersListModel.remove(selectionIndex);
					
					updateLogPanel("Node removed from outputs.", Color.BLACK);
					
					if (outputLayersListModel.isEmpty())
						outputLayersListModel.addElement("No output layer");
					
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
	
	private static void updateLogPanel(String logText, Color color) {
		logPanel.removeAll();
		JLabel logMessage = new JLabel(logText);
		logMessage.setForeground(color);
		logPanel.add(logMessage);
		logPanel.repaint();
		logPanel.revalidate();
	}

}
