// QuPath Tile Locator GUI
// This script creates a GUI for creating annotations from filenames with coordinate information
// Enhanced with auto-update on paste, annotation locking, and automatic image selection

import javax.swing.*
import java.awt.*
import java.awt.event.*
import javax.swing.event.*
import qupath.lib.roi.ROIs
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.gui.dialogs.Dialogs
import javafx.application.Platform

// Define a function to extract coordinates from a filename
def extractCoordinates(String text) {
    def matcher = text =~ /\[d=(\d+),x=(\d+),y=(\d+),w=(\d+),h=(\d+)\]/
    if (matcher.find()) {
        return [
            d: matcher.group(1) as int,
            x: matcher.group(2) as int,
            y: matcher.group(3) as int,
            w: matcher.group(4) as int,
            h: matcher.group(5) as int
        ]
    }
    
    return [d: 0, x: 0, y: 0, w: 0, h: 0]
}

// Extract the bracket content to use as name
def extractBracketContent(String text) {
    def matcher = text =~ /\[(.*?)\]/
    if (matcher.find()) {
        return matcher.group(1)
    }
    return ""
}

// Extract image name from filename (before the bracket part)
def extractImageName(String text) {
    // Remove everything from the first '[' onwards
    def bracketIndex = text.indexOf('[')
    if (bracketIndex > 0) {
        return text.substring(0, bracketIndex).trim()
    }
    return text.trim()
}

// Function to find and select image in project
def selectImageInProject(String imageName) {
    def project = getProject()
    if (project == null) {
        Platform.runLater {
            Dialogs.showErrorMessage("Error", "No project is currently open")
        }
        return false
    }
    
    def imageList = project.getImageList()
    if (imageList.isEmpty()) {
        Platform.runLater {
            Dialogs.showErrorMessage("Error", "No images found in the current project")
        }
        return false
    }
    
    // Try to find exact match first
    def targetEntry = null
    for (entry in imageList) {
        def entryName = entry.getImageName()
        
        // Remove .svs extension for comparison
        def nameWithoutExt = entryName.replaceAll(/\.svs$/, "")
        
        if (nameWithoutExt.equals(imageName)) {
            targetEntry = entry
            break
        }
    }
    
    // If no exact match, try partial match
    if (targetEntry == null) {
        for (entry in imageList) {
            def entryName = entry.getImageName()
            def nameWithoutExt = entryName.replaceAll(/\.svs$/, "")
            
            if (nameWithoutExt.contains(imageName) || imageName.contains(nameWithoutExt)) {
                targetEntry = entry
                break
            }
        }
    }
    
    if (targetEntry != null) {
        try {
            // Read image data first (this is safe in any thread)
            def imageData = targetEntry.readImageData()
            
            // Set the image data on JavaFX thread
            Platform.runLater {
                try {
                    def viewer = getCurrentViewer()
                    viewer.setImageData(imageData)
                    println("Successfully selected image: " + targetEntry.getImageName())
                } catch (Exception ex) {
                    println("Failed to set image data: " + ex.getMessage())
                    Dialogs.showErrorMessage("Error", "Failed to load image: " + ex.getMessage())
                }
            }
            return true
        } catch (Exception ex) {
            Platform.runLater {
                Dialogs.showErrorMessage("Error", "Failed to load image: " + ex.getMessage())
            }
            return false
        }
    } else {
        // Show available images for debugging
        def availableImages = imageList.collect { it.getImageName() }.join(", ")
        Platform.runLater {
            Dialogs.showErrorMessage("Image Not Found", 
                "Could not find image matching: '" + imageName + "'\n\n" +
                "Available images: " + availableImages)
        }
        return false
    }
}

// Create and show the GUI
void createAndShowGUI() {
    // Create the main frame
    JFrame frame = new JFrame("QuPath Tile Locator - Enhanced")
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
    frame.setSize(520, 550)
    frame.setLocationRelativeTo(null)
    frame.setLayout(new BorderLayout())
    frame.setAlwaysOnTop(true) // Always on top
    
    // Create the title panel
    JPanel titlePanel = new JPanel()
    titlePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))
    JLabel titleLabel = new JLabel("QuPath Tile Locator - Enhanced")
    titleLabel.setFont(new Font("Sans-serif", Font.BOLD, 14))
    titlePanel.add(titleLabel)
    frame.add(titlePanel, BorderLayout.NORTH)
    
    // Create the content panel
    JPanel contentPanel = new JPanel(new BorderLayout())
    
    // Create the filename panel
    JPanel filenamePanel = new JPanel(new BorderLayout())
    filenamePanel.setBorder(BorderFactory.createTitledBorder("Filename"))
    JTextArea filenameArea = new JTextArea()
    filenameArea.setLineWrap(true)
    filenameArea.setWrapStyleWord(true)
    JScrollPane scrollPane = new JScrollPane(filenameArea)
    scrollPane.setPreferredSize(new Dimension(480, 80))
    filenamePanel.add(scrollPane, BorderLayout.CENTER)
    
    // Add instructions label
    JLabel instructionLabel = new JLabel("Paste filename and press Enter to auto-select image and create annotation")
    instructionLabel.setFont(new Font("Sans-serif", Font.ITALIC, 11))
    instructionLabel.setForeground(Color.BLUE)
    filenamePanel.add(instructionLabel, BorderLayout.SOUTH)
    
    contentPanel.add(filenamePanel, BorderLayout.NORTH)
    
    // Create image name display panel
    JPanel imagePanel = new JPanel(new BorderLayout())
    imagePanel.setBorder(BorderFactory.createTitledBorder("Detected Image"))
    JTextField imageField = new JTextField()
    imageField.setEditable(false)
    imageField.setBackground(Color.LIGHT_GRAY)
    imagePanel.add(imageField, BorderLayout.CENTER)
    
    // Create middle panel for image and coordinates
    JPanel middlePanel = new JPanel(new BorderLayout())
    middlePanel.add(imagePanel, BorderLayout.NORTH)
    
    // Create the coordinates panel
    JPanel coordsPanel = new JPanel(new GridLayout(3, 2, 10, 10))
    coordsPanel.setBorder(BorderFactory.createTitledBorder("Coordinates"))
    
    // X coordinate
    JPanel xPanel = new JPanel(new BorderLayout())
    JLabel xLabel = new JLabel("X Coordinate: ")
    JTextField xField = new JTextField(10)
    xPanel.add(xLabel, BorderLayout.WEST)
    xPanel.add(xField, BorderLayout.CENTER)
    coordsPanel.add(xPanel)
    
    // Y coordinate
    JPanel yPanel = new JPanel(new BorderLayout())
    JLabel yLabel = new JLabel("Y Coordinate: ")
    JTextField yField = new JTextField(10)
    yPanel.add(yLabel, BorderLayout.WEST)
    yPanel.add(yField, BorderLayout.CENTER)
    coordsPanel.add(yPanel)
    
    // Width
    JPanel wPanel = new JPanel(new BorderLayout())
    JLabel wLabel = new JLabel("Width: ")
    JTextField wField = new JTextField(10)
    wPanel.add(wLabel, BorderLayout.WEST)
    wPanel.add(wField, BorderLayout.CENTER)
    coordsPanel.add(wPanel)
    
    // Height
    JPanel hPanel = new JPanel(new BorderLayout())
    JLabel hLabel = new JLabel("Height: ")
    JTextField hField = new JTextField(10)
    hPanel.add(hLabel, BorderLayout.WEST)
    hPanel.add(hField, BorderLayout.CENTER)
    coordsPanel.add(hPanel)
    
    // Downsample
    JPanel dPanel = new JPanel(new BorderLayout())
    JLabel dLabel = new JLabel("Downsample: ")
    JTextField dField = new JTextField(10)
    dPanel.add(dLabel, BorderLayout.WEST)
    dPanel.add(dField, BorderLayout.CENTER)
    coordsPanel.add(dPanel)
    
    middlePanel.add(coordsPanel, BorderLayout.CENTER)
    contentPanel.add(middlePanel, BorderLayout.CENTER)
    frame.add(contentPanel, BorderLayout.CENTER)
    
    // Function to create annotation with automatic image selection
    def createAnnotationFromFields = {
        try {
            def filename = filenameArea.getText().trim()
            if (filename.isEmpty()) {
                Platform.runLater {
                    Dialogs.showErrorMessage("Error", "Please enter a filename")
                }
                return
            }
            
            def x = Integer.parseInt(xField.getText())
            def y = Integer.parseInt(yField.getText())
            def w = Integer.parseInt(wField.getText())
            def h = Integer.parseInt(hField.getText())
            def imageName = extractImageName(filename)
            def bracketContent = extractBracketContent(filename)
            
            // Find the target image entry
            def project = getProject()
            if (project == null) {
                Platform.runLater {
                    Dialogs.showErrorMessage("Error", "No project is currently open")
                }
                return
            }
            
            def imageList = project.getImageList()
            if (imageList.isEmpty()) {
                Platform.runLater {
                    Dialogs.showErrorMessage("Error", "No images found in the current project")
                }
                return
            }
            
            // Try to find exact match first
            def targetEntry = null
            for (entry in imageList) {
                def entryName = entry.getImageName()
                def nameWithoutExt = entryName.replaceAll(/\.svs$/, "")
                
                if (nameWithoutExt.equals(imageName)) {
                    targetEntry = entry
                    break
                }
            }
            
            // If no exact match, try partial match
            if (targetEntry == null) {
                for (entry in imageList) {
                    def entryName = entry.getImageName()
                    def nameWithoutExt = entryName.replaceAll(/\.svs$/, "")
                    
                    if (nameWithoutExt.contains(imageName) || imageName.contains(nameWithoutExt)) {
                        targetEntry = entry
                        break
                    }
                }
            }
            
            if (targetEntry == null) {
                def availableImages = imageList.collect { it.getImageName() }.join(", ")
                Platform.runLater {
                    Dialogs.showErrorMessage("Image Not Found", 
                        "Could not find image matching: '" + imageName + "'\n\n" +
                        "Available images: " + availableImages)
                }
                return
            }
            
            // Read image data (safe in any thread)
            def imageData = targetEntry.readImageData()
            
            // Execute all QuPath/JavaFX operations on the correct thread
            Platform.runLater {
                try {
                    // Set image data
                    def viewer = getCurrentViewer()
                    viewer.setImageData(imageData)
                    println("Successfully selected image: " + targetEntry.getImageName())
                    
                    // Wait a moment for image to load
                    Thread.sleep(100)
                    
                    // Create ROI
                    def roi = ROIs.createRectangleROI(x, y, w, h, ImagePlane.getPlane(0, 0))
                    def annotation = PathObjects.createAnnotationObject(roi)
                    
                    // Set name property to bracket content
                    annotation.setName(bracketContent)
                    
                    // Lock the annotation
                    annotation.setLocked(true)
                    
                    // Add to image
                    addObject(annotation)
                    
                    // Update the hierarchy to refresh the display
                    fireHierarchyUpdate()
                    
                    println("Created locked annotation at (${x}, ${y}) with size ${w}x${h}, name: ${bracketContent}")
                    
                    try {
                        getCurrentViewer().setSelectedObject(annotation)
                        // Center the view on the annotation using QuPath's built-in method
                        def currentViewer = getCurrentViewer()
                        if (currentViewer != null) {
                            currentViewer.centerROI(roi)
                        }
                    } catch (Exception ex) {
                        println("Could not select/center the annotation: " + ex.getMessage())
                    }
                    
                    // Show success message
                    Dialogs.showInfoNotification("Annotation Created", 
                        "Selected image: ${imageName}\nCreated locked annotation at (${x}, ${y}) with size ${w}x${h}")
                    
                } catch (Exception ex) {
                    println("Error in JavaFX thread: " + ex.getMessage())
                    Dialogs.showErrorMessage("Error", "Failed to create annotation: " + ex.getMessage())
                }
            }
            
            // Clear only the filename area for next input, keep other fields
            SwingUtilities.invokeLater {
                filenameArea.setText("")
                // Do NOT clear imageField and coordinate fields - keep them for reference
            }
            
        } catch (NumberFormatException nfe) {
            Platform.runLater {
                Dialogs.showErrorMessage("Error", "Invalid coordinate values. Please check the input.")
            }
        } catch (Exception ex) {
            Platform.runLater {
                Dialogs.showErrorMessage("Error", "Failed to process request: " + ex.getMessage())
            }
        }
    }
    
    // Create the button panel
    JPanel buttonPanel = new JPanel(new FlowLayout())
    buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))
    
    // Create annotation button
    JButton createButton = new JButton("Select Image & Create Annotation")
    createButton.setPreferredSize(new Dimension(200, 30))
    createButton.addActionListener(new ActionListener() {
        @Override
        void actionPerformed(ActionEvent e) {
            createAnnotationFromFields()
        }
    })
    buttonPanel.add(createButton)
    
    // Clear all fields button
    JButton clearButton = new JButton("Clear All")
    clearButton.setPreferredSize(new Dimension(100, 30))
    clearButton.addActionListener(new ActionListener() {
        @Override
        void actionPerformed(ActionEvent e) {
            filenameArea.setText("")
            imageField.setText("")
            xField.setText("")
            yField.setText("")
            wField.setText("")
            hField.setText("")
            dField.setText("")
        }
    })
    buttonPanel.add(clearButton)
    
    // Close button
    JButton closeButton = new JButton("Close")
    closeButton.setPreferredSize(new Dimension(80, 30))
    closeButton.addActionListener(new ActionListener() {
        @Override
        void actionPerformed(ActionEvent e) {
            frame.dispose()
        }
    })
    buttonPanel.add(closeButton)
    
    frame.add(buttonPanel, BorderLayout.SOUTH)
    
    // Add document listener to automatically extract coordinates and image name when text changes
    filenameArea.getDocument().addDocumentListener(new DocumentListener() {
        void updateFields() {
            SwingUtilities.invokeLater {
                def filename = filenameArea.getText().trim()
                if (filename.isEmpty()) {
                    // Don't clear any fields when filename is empty - preserve all for next operation
                    return
                }
                
                // Extract and display image name
                def imageName = extractImageName(filename)
                imageField.setText(imageName)
                
                // Extract coordinates
                def coords = extractCoordinates(filename)
                if (coords.x != 0 || coords.y != 0 || coords.w != 0 || coords.h != 0) {
                    xField.setText(coords.x.toString())
                    yField.setText(coords.y.toString())
                    wField.setText(coords.w.toString())
                    hField.setText(coords.h.toString())
                    dField.setText(coords.d.toString())
                }
            }
        }
        
        @Override
        void insertUpdate(DocumentEvent e) { updateFields() }
        
        @Override
        void removeUpdate(DocumentEvent e) { updateFields() }
        
        @Override
        void changedUpdate(DocumentEvent e) { updateFields() }
    })
    
    // Add Enter key listener for auto-creation
    filenameArea.addKeyListener(new KeyAdapter() {
        @Override
        void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                // Check if coordinates are valid
                def filename = filenameArea.getText().trim()
                def coords = extractCoordinates(filename)
                if (coords.x != 0 || coords.y != 0 || coords.w != 0 || coords.h != 0) {
                    // Auto-create annotation when Enter is pressed and coordinates are valid
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        void run() {
                            createAnnotationFromFields()
                        }
                    })
                }
                e.consume() // Prevent newline in text area
            }
        }
    })
    
    // Display the window
    frame.pack()
    frame.setVisible(true)
}

// Run the GUI on the EDT
SwingUtilities.invokeLater(new Runnable() {
    @Override
    void run() {
        createAndShowGUI()
    }
})

println("Enhanced GUI with automatic image selection is running")