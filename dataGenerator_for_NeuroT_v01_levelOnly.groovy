import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.LabeledImageServer;
import qupath.lib.images.writers.TileExporter;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.transform.Field

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

// ====================== Parameters ======================

// Output folder name within QuPath project (will be created under PROJECT_BASE_DIR)
@Field String outputFolderName = "tumor_dataset_04"  

// Model type: "CLA" (Classification), "OBD" (Object Detection), "SEG" (Segmentation)
@Field String modelType = "SEG"

// Target class names to extract (used in JSON filename)
// Examples: ["Negative"], ["Tumor", "Stroma"], ["Region*"]
@Field ArrayList<String> classNames = new ArrayList<>(Arrays.asList("Negative"))  

// Image pyramid level for tile extraction
@Field String level = "0"

// Tile size in pixels
@Field String patchSize = "512"

// Overlap between tiles in pixels
@Field String overlap = "0"

// Dataset name (will be used in JSON filename)
@Field String datasetName = "Negative_03"

// Extract only tiles that contain annotations
@Field boolean annotatedTilesOnly = true

// ========================================================

// Check if project is open
def project = QPEx.getProject()
if (project == null) {
    logger.error("No project is open. Please open a QuPath project first.")
    return
}

// Build output path using PROJECT_BASE_DIR
def projectBasePath = buildFilePath(PROJECT_BASE_DIR, outputFolderName)
mkdirs(projectBasePath)

// Get current image data
ImageData<BufferedImage> imageData = QPEx.getCurrentImageData();

// Log processing information
String imageName = imageData.getServer().getMetadata().getName();
logger.info("Processing image: {}", imageName);
logger.info("Target classes: {}", classNames);

// Create configuration map for DataGenerator
Map<String, Object> config = [
    projectBasePath: projectBasePath,
    imageData: imageData,
    modelType: modelType,
    classNames: classNames,
    level: level,
    patchSize: patchSize,
    overlap: overlap,
    datasetName: datasetName,
    annotatedTilesOnly: annotatedTilesOnly
]

// Initialize and run DataGenerator
generator = new DataGenerator(config);

try {
    generator.generate(); // Execute createTrainDataset, processTiles, saveJson in sequence
} catch (Exception e) {
    e.printStackTrace();
}

public class DataGenerator {
	
    private static final Logger logger = LoggerFactory.getLogger(DataGenerator.class);
    
    private String projectBasePath;
    private ImageData<BufferedImage> imageData;
    private String modelType;
    private ArrayList<String> classNames;
    private String patchSize;
    private String overlap;
    private String datasetName;
    private boolean annotatedTilesOnly;
    private String level;
    private double downsample;
    private Collection<PathObject> selectedAnnos = new ArrayList<>();

    private Path dbPath;
    private Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Map<String, TileData> groupedAnnotations = new HashMap<>();
    
    private String imageExtension = ".png";

    /**
     * Constructor using configuration map
     * @param config Map containing all configuration parameters
     */
    public DataGenerator(Map<String, Object> config) {
        // Extract and validate required parameters
        this.projectBasePath = (String) config.get("projectBasePath");
        this.imageData = (ImageData<BufferedImage>) config.get("imageData");
        this.modelType = (String) config.getOrDefault("modelType", "CLA");
        this.classNames = (ArrayList<String>) config.getOrDefault("classNames", 
            new ArrayList<>(Arrays.asList("Tumor", "Stroma", "Immune cells")));
        this.datasetName = (String) config.getOrDefault("datasetName", "tmp");
        this.annotatedTilesOnly = (Boolean) config.getOrDefault("annotatedTilesOnly", true);
        this.level = (String) config.getOrDefault("level", "1");
        this.patchSize = (String) config.getOrDefault("patchSize", "512");
        this.overlap = (String) config.getOrDefault("overlap", "0");
        
        // Calculate downsample factor from level
        this.downsample = imageData.getServer().getMetadata().getDownsampleForLevel(Integer.parseInt(level));
        
        // Set database path
        this.dbPath = Paths.get(projectBasePath, datasetName);
        
        // Log configuration
        logger.info("DataGenerator initialized with modelType: {}, level: {}, patchSize: {}", 
            modelType, level, patchSize);
    }

    /**
     * Main generation method - executes the full pipeline
     */
    public void generate() throws Exception {
        createTrainDataset();
        processTiles();
        saveJson();
    }

    /**
     * Create training dataset by extracting tiles from annotations
     */
    public void createTrainDataset() throws Exception {
    	
    	Collection<PathObject> removedAnnos = new ArrayList<>();
    	Set<String> foundClassNames = new HashSet<>();  // Store actual found class names
    	
    	// Filter annotations by class names
    	for (PathObject anno : imageData.getHierarchy().getAnnotationObjects()) {
    	    PathClass annoClass = anno.getPathClass();
    	    if (annoClass == null) {
    	        continue;
    	    }

    	    // Check if annotation class matches any target class
    	    boolean matches = classNames.stream()
    	        .anyMatch(name -> annoClass.equals(QPEx.getPathClass(name)));

    	    if (matches) {
    	        anno.setLocked(true);
    	        selectedAnnos.add(anno);
    	        foundClassNames.add(annoClass.getName());  // Store found class name
    	    } else {
    	    	removedAnnos.add(anno); 
    	    }
    	}

    	// Log warning if no annotations found (no popup)
    	if (selectedAnnos.isEmpty()) {
    	    String imageName = imageData.getServer().getMetadata().getName();
    	    logger.warn("No annotations found for classes {} in image: {}", classNames, imageName);
    	    return;  // Skip processing for this image
    	} else {
    	    logger.info("Found {} annotations with classes: {}", selectedAnnos.size(), foundClassNames);
    	}
    	
    	// Temporarily remove non-target annotations
    	imageData.getHierarchy().removeObjects(removedAnnos, true);

    	// Create output directory
        try {
            if (!Files.exists(dbPath)) {
                Files.createDirectory(dbPath);
            }
        } catch (Exception e) {
            logger.error("Create directory error - Failed to create directory: {}", dbPath, e);
            throw new Exception("Create directory error", e);
        }
        
        // Configure labeled image server
        LabeledImageServer.Builder builder = new LabeledImageServer.Builder(imageData);
        builder.backgroundLabel(0, ColorTools.WHITE)
        	   .useInstanceLabels()
               .downsample(downsample)
               .multichannelOutput(false);

        // Configure tile exporter
        TileExporter exporter = new TileExporter(imageData);
        exporter.downsample(downsample)
                .imageExtension(imageExtension)
                .tileSize(Integer.parseInt(patchSize))
                .overlap(Integer.parseInt(overlap))
                .includePartialTiles(true)
                .annotatedTilesOnly(annotatedTilesOnly);
        
        // Export tiles
        try {
            exporter.writeTiles(dbPath.toString());
            logger.info("Tiles exported to: {}", dbPath);
        } catch (IOException e) {
            logger.error("Tile export failed", e);
        } finally {
        	// Restore removed annotations
        	imageData.getHierarchy().addObjects(removedAnnos); 
        }
    }
    
    /**
     * Process exported tiles and extract annotation information
     */
    public void processTiles() {
        // Skip if no annotations were found
        if (selectedAnnos.isEmpty()) {
            logger.info("No annotations to process, skipping tile processing");
            return;
        }

    	File folder = dbPath.toFile();
        File[] listOfFiles = folder.listFiles();

        // Create images subdirectory
        File processedDir = new File(folder, "images");
        if (!processedDir.exists()) {
            processedDir.mkdirs();
        }
        
        if (listOfFiles == null) return;

        // Process each tile file
        for (File tile : listOfFiles) {
        	
            if (tile.isDirectory()) {
                if (tile.getName().equals("images")) continue;
                continue;
            }
            
            if (tile.isDirectory() || !tile.getName().endsWith(imageExtension)) continue;

            String tileName = tile.getName();
            // Extract tile coordinates from filename
            Pattern pattern = Pattern.compile("\\[.*x=(\\d+),y=(\\d+),w=(\\d+),h=(\\d+)\\]"); 
            Matcher matcher = pattern.matcher(tileName);
            if (!matcher.find()) {
                continue;
            }

            int tileX = Integer.parseInt(matcher.group(1));
            int tileY = Integer.parseInt(matcher.group(2));
            int tileWidth = Integer.parseInt(matcher.group(3));
            int tileHeight = Integer.parseInt(matcher.group(4));

            // Create ROI for current tile
            ROI tileROI = ROIs.createRectangleROI(tileX, tileY, tileWidth, tileHeight, null);

            List<RegionLabel> regionLabels = new ArrayList<>();
            String tileClassLabel = "";
            Map<String, Double> classAreaMap = new HashMap<>();

            // Process annotations within tile
            for (PathObject annotation : selectedAnnos) {
                ROI roi = annotation.getROI();
                ROI intersectionROI = RoiTools.intersection(Arrays.asList(roi, tileROI));
                double area = intersectionROI.getArea();
                if (area <= 0.0) continue;

                PathClass pathClass = annotation.getPathClass();
                String className = pathClass != null ? pathClass.getName() : "Unknown";

                // Process based on model type
                switch (modelType) {
                    case "OBD":  // Object Detection - bounding boxes
                        regionLabels.add(new RegionLabel(className, "Rect",
                                (int)((intersectionROI.getBoundsX() - tileX) / downsample),
                                (int)((intersectionROI.getBoundsY() - tileY) / downsample),
                                (int)(intersectionROI.getBoundsWidth() / downsample),
                                (int)(intersectionROI.getBoundsHeight() / downsample)));
                        break;
                    case "SEG":  // Segmentation - polygons
                        List<List<Integer>> newPoints = new ArrayList<>();
                        for (Point2 pt : intersectionROI.getAllPoints()) {
                            newPoints.add(Arrays.asList(
                                    (int)((pt.getX() - tileX) / downsample),
                                    (int)((pt.getY() - tileY) / downsample)));
                        }
                        regionLabels.add(new RegionLabel(className, "PolyGon", newPoints));
                        break;
                    case "CLA":  // Classification - largest area class
                        classAreaMap.merge(className, area, Double::sum);
                        break;
                }
            }

            // For classification, assign class with largest area
            if (modelType.equals("CLA") && !classAreaMap.isEmpty()) {
                tileClassLabel = Collections.max(classAreaMap.entrySet(), Map.Entry.comparingByValue()).getKey();
            }

            String fileName = tile.getName().split(Pattern.quote(imageExtension))[0] + ".png";

            // Store tile data
            groupedAnnotations.computeIfAbsent(fileName, k ->
                new TileData(fileName, modelType, "", Integer.parseInt(patchSize), Integer.parseInt(patchSize))
    		);
            
            if (modelType.equals("CLA")) {
                groupedAnnotations.get(fileName).classLabel = tileClassLabel;
            } else {
                groupedAnnotations.get(fileName).regionLabel.addAll(regionLabels);
            }
            
            // Move tile to images subdirectory
            File destFile = new File(processedDir, tile.getName());
            try {
                Files.move(tile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.error("Failed to move file: {}", tile.getName(), e);
            }
        }
        
        logger.info("Processed {} tiles", groupedAnnotations.size());
    }
    
    /**
     * Save annotation data to JSON file
     */
    public void saveJson() throws IOException {
        // Skip if no annotations were found
        if (selectedAnnos.isEmpty()) {
            logger.info("No annotations to save, skipping JSON generation");
            return;
        }
    	
    	String fullName = imageData.getServer().getMetadata().getName(); 
    	String baseName = fullName;
    	int lastDot = fullName.lastIndexOf('.');
    	if (lastDot > 0) {
    	    baseName = fullName.substring(0, lastDot);
    	}
    	
    	String projectName = QPEx.getProject().getPath().getParent().getFileName().toString();
    	
    	// Create JSON filename with project name and dataset name
    	String jsonFileName = projectName + "_" + datasetName + "_output.json";
    	File outputFile = dbPath.resolve(jsonFileName).toFile();
    	
    	logger.info("Creating JSON output file: {}", jsonFileName);
    	
        Map<String, TileData> existingAnnotations = new HashMap<>();

        // Load existing data if file exists
        if (outputFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(outputFile))) {
                Map<?, ?> parsedJson = gson.fromJson(reader, Map.class);
                List<?> existingData = (List<?>) parsedJson.get("data");
                if (existingData != null) {
                    for (Object tileObj : existingData) {
                        Map<?, ?> tileMap = (Map<?, ?>) tileObj;
                        String fileName = (String) tileMap.get("fileName");
                        
                        TileData tileData = gson.fromJson(gson.toJson(tileMap), TileData.class);
                        
                        existingAnnotations.put(fileName, tileData);
                    }
                }
            } catch (IOException e) {
                logger.error("Error reading existing JSON file", e);
            }
        }

        // Merge with new annotations
        existingAnnotations.putAll(groupedAnnotations);
        
        // Create final JSON structure
        Map<String, Object> finalJson = new HashMap<>();
        finalJson.put("label_type", modelType);
        finalJson.put("source", "labelset");
        finalJson.put("data", new ArrayList<>(existingAnnotations.values()));

        // Write JSON file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(gson.toJson(finalJson));
            logger.info("JSON file saved successfully: {}", outputFile.getAbsolutePath());
        }
    }

    /**
     * Class representing region label information
     */
    class RegionLabel {
        String className;
        String type;
        List<List<Integer>> points;  // For polygons
        int x, y, width, height;     // For rectangles
        
        public RegionLabel() {
        }

        // Constructor for rectangles (Object Detection)
        public RegionLabel(String className, String type, int x, int y, int width, int height) {
            this.className = className;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.points = null;  
        }

        // Constructor for polygons (Segmentation)
        public RegionLabel(String className, String type, List<List<Integer>> points) {
            this.className = className;
            this.type = type;
            this.points = points;
            this.x = 0;
            this.y = 0;
            this.width = 0;
            this.height = 0;
        }
    }
    
    /**
     * Class representing tile data structure for JSON output
     */
    class TileData {
        String fileName;
        String set;
        String classLabel;
        List<RegionLabel> regionLabel = new ArrayList<>();
        int retestset = 0;
        int rotation_angle = 0;
        int width;
        int height;

        public TileData() {}

        public TileData(String fileName, String set, String classLabel, int width, int height) {
            this.fileName = fileName;
            this.set = set;
            this.classLabel = classLabel;
            this.width = width;
            this.height = height;
        }
    }
}