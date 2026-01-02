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
import java.awt.image.Raster;
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

// ================================================================
// DATASET CONFIGURATION
// ================================================================

// Output folder name (created under QuPath project directory)
@Field String outputFolderName = "tumor_dataset_04"  

// Dataset name (used in JSON filename and subfolder)
@Field String datasetName = "Negative_03"

// ================================================================
// MODEL CONFIGURATION
// ================================================================

// Model type: "CLA" (Classification), "OBD" (Object Detection), "SEG" (Segmentation)
@Field String modelType = "SEG"

// Target class names to extract
// Examples: ["Tumor"], ["Tumor", "Stroma"], ["Negative"], ["Tissue"]
@Field ArrayList<String> classNames = new ArrayList<>(Arrays.asList("Negative"))

// ================================================================
// TILE EXTRACTION SETTINGS
// ================================================================

// Downsample factor for tile extraction
// Examples: 1.0 (level 0, full resolution), 2.0, 4.0 (level 1), 16.0 (level 2)
@Field double downsample = 2.0

// Tile size in pixels (at the downsampled resolution)
@Field int patchSize = 512

// Overlap between tiles in pixels
@Field int overlap = 0

// Extract only tiles that contain annotations
// true: only tiles with annotations / false: all tiles in annotated regions
@Field boolean annotatedTilesOnly = true

// ================================================================
// TISSUE FILTERING SETTINGS
// ================================================================

// Enable tissue quality filtering (recommended for whole slide images)
@Field boolean enableTissueFiltering = true

// Glass/background detection threshold (range: 0-255)
// - Higher value (e.g., 100): stricter, only dark regions counted as tissue
// - Lower value (e.g., 20): more lenient, lighter regions also counted as tissue
// - Set to 0: disable glass detection (all pixels counted as tissue)
// Recommended: 50 for H&E stained slides
@Field double glassThreshold = 50

// Minimum tissue percentage required to keep a tile (range: 0.0-1.0)
// - 0.0: keep all tiles (no filtering)
// - 0.25: keep tiles with ≥25% tissue
// - 0.5: keep tiles with ≥50% tissue
// - 1.0: keep only tiles that are 100% tissue
// Recommended: 0.25 for general use
@Field double percentageThreshold = 0.25

// ================================================================
// PRESET CONFIGURATIONS (Uncomment to use)
// ================================================================

// PRESET 1: High Quality Dataset (strict filtering)
// enableTissueFiltering = true
// glassThreshold = 50
// percentageThreshold = 0.5

// PRESET 2: Maximum Data Collection (lenient filtering)
// enableTissueFiltering = true
// glassThreshold = 30
// percentageThreshold = 0.1

// PRESET 3: No Filtering (use all tiles)
// enableTissueFiltering = false
// glassThreshold = 0
// percentageThreshold = 0.0

// PRESET 4: Very Strict (only high-quality tissue tiles)
// enableTissueFiltering = true
// glassThreshold = 100
// percentageThreshold = 0.8

// ================================================================
// SCRIPT EXECUTION (Do not modify below)
// ================================================================

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
logger.info("=" * 60);
logger.info("Processing image: {}", imageName);
logger.info("Target classes: {}", classNames);
logger.info("Model type: {}", modelType);
logger.info("Downsample: {}", downsample);
logger.info("Patch size: {}x{}", patchSize, patchSize);
logger.info("Tissue filtering: {}", enableTissueFiltering ? "ENABLED" : "DISABLED");
if (enableTissueFiltering) {
    logger.info("  - Glass threshold: {}", glassThreshold);
    logger.info("  - Tissue percentage threshold: {}%", (percentageThreshold * 100));
}
logger.info("=" * 60);

// Create configuration map for DataGenerator
Map<String, Object> config = [
    projectBasePath: projectBasePath,
    imageData: imageData,
    modelType: modelType,
    classNames: classNames,
    downsample: downsample,
    patchSize: patchSize,
    overlap: overlap,
    datasetName: datasetName,
    annotatedTilesOnly: annotatedTilesOnly,
    enableTissueFiltering: enableTissueFiltering,
    glassThreshold: glassThreshold,
    percentageThreshold: percentageThreshold
]

// Initialize and run DataGenerator
generator = new DataGenerator(config);

try {
    generator.generate(); // Execute createTrainDataset, processTiles, saveJson in sequence
} catch (Exception e) {
    logger.error("Error during tile generation", e);
    e.printStackTrace();
}

public class DataGenerator {
	
    private static final Logger logger = LoggerFactory.getLogger(DataGenerator.class);
    
    private String projectBasePath;
    private ImageData<BufferedImage> imageData;
    private String modelType;
    private ArrayList<String> classNames;
    private int patchSize;
    private int overlap;
    private String datasetName;
    private boolean annotatedTilesOnly;
    private double downsample;
    private boolean enableTissueFiltering;
    private double glassThreshold;
    private double percentageThreshold;
    private Collection<PathObject> selectedAnnos = new ArrayList<>();

    private Path dbPath;
    private Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Map<String, TileData> groupedAnnotations = new HashMap<>();
    
    private String imageExtension = ".png";
    
    // Statistics tracking
    private int totalTilesExported = 0;
    private int tilesKeptAfterFiltering = 0;
    private int tilesRemovedLowTissue = 0;

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
        this.patchSize = (Integer) config.getOrDefault("patchSize", 512);
        this.overlap = (Integer) config.getOrDefault("overlap", 0);
        this.downsample = (Double) config.getOrDefault("downsample", 1.0);
        
        // Tissue filtering parameters
        this.enableTissueFiltering = (Boolean) config.getOrDefault("enableTissueFiltering", false);
        this.glassThreshold = (Double) config.getOrDefault("glassThreshold", 50.0);
        this.percentageThreshold = (Double) config.getOrDefault("percentageThreshold", 0.25);
        
        // Set database path
        this.dbPath = Paths.get(projectBasePath, datasetName);
        
        // Log configuration
        logger.info("DataGenerator initialized:");
        logger.info("  Model type: {}", modelType);
        logger.info("  Downsample: {}", downsample);
        logger.info("  Patch size: {}", patchSize);
        logger.info("  Tissue filtering: {}", enableTissueFiltering);
    }

    /**
     * Main generation method - executes the full pipeline
     */
    public void generate() throws Exception {
        createTrainDataset();
        processTiles();
        saveJson();
        printStatistics();
    }

    /**
     * Create training dataset by extracting tiles from annotations
     */
    public void createTrainDataset() throws Exception {
    	
    	Collection<PathObject> removedAnnos = new ArrayList<>();
    	Set<String> foundClassNames = new HashSet<>();
    	
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
    	        foundClassNames.add(annoClass.getName());
    	    } else {
    	    	removedAnnos.add(anno); 
    	    }
    	}

    	// Log warning if no annotations found
    	if (selectedAnnos.isEmpty()) {
    	    String imageName = imageData.getServer().getMetadata().getName();
    	    logger.warn("No annotations found for classes {} in image: {}", classNames, imageName);
    	    return;
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
            logger.error("Failed to create directory: {}", dbPath, e);
            throw new Exception("Create directory error", e);
        }
        
        // Configure tile exporter
        TileExporter exporter = new TileExporter(imageData);
        exporter.downsample(downsample)
                .imageExtension(imageExtension)
                .tileSize(patchSize)
                .overlap(overlap)
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
     * Process exported tiles and apply tissue filtering
     */
    public void processTiles() {
        // Skip if no annotations were found
        if (selectedAnnos.isEmpty()) {
            logger.info("No annotations to process, skipping tile processing");
            return;
        }
    	
        File processedDir = new File(dbPath.toString(), "images");
        if (!processedDir.exists()) {
            processedDir.mkdirs();
        }

        File[] tiles = dbPath.toFile().listFiles();
        if (tiles == null || tiles.length == 0) {
            logger.warn("No tiles found in directory: {}", dbPath);
            return;
        }

        logger.info("Processing {} files...", tiles.length);
        totalTilesExported = tiles.length - 1; // Exclude 'images' directory

        for (File tile : tiles) {
            // Skip images subdirectory and non-PNG files
            if (tile.isDirectory()) {
                if (tile.getName().equals("images")) continue;
                continue;
            }
            
            if (!tile.getName().endsWith(imageExtension)) continue;

            String tileName = tile.getName();
            
            // Apply tissue filtering if enabled
            if (enableTissueFiltering) {
                double tissuePercentage = calculateTissuePercentage(tile);
                
                // If calculation failed (returned -1), skip filtering
                if (tissuePercentage >= 0 && tissuePercentage < percentageThreshold) {
                    logger.debug("Removing tile {} - low tissue content: {}%", 
                        tileName, String.format("%.1f", tissuePercentage * 100));
                    tile.delete();
                    tilesRemovedLowTissue++;
                    continue;
                }
            }
            
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
                new TileData(fileName, modelType, "", patchSize, patchSize)
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
                tilesKeptAfterFiltering++;
            } catch (IOException e) {
                logger.error("Failed to move file: {}", tile.getName(), e);
            }
        }
        
        logger.info("Tile processing completed: {} tiles kept", groupedAnnotations.size());
    }
    
    /**
     * Calculate tissue percentage in a tile (OPTIMIZED VERSION)
     * Combines best practices from both implementations
     * @param tileFile The tile image file
     * @return Percentage of tissue pixels (0.0 to 1.0), or -1.0 on error
     */
    private double calculateTissuePercentage(File tileFile) {
        try {
            // Read image with null check (from GPT version)
            BufferedImage image = ImageIO.read(tileFile);
            if (image == null) {
                logger.warn("Failed to read image: {}", tileFile.getName());
                return -1.0;  // Return -1 to indicate error
            }
            
            Raster raster = image.getRaster();
            int width = image.getWidth();
            int height = image.getHeight();
            
            // Auto-detect number of channels (from GPT version)
            int nbChannels = raster.getNumBands();
            
            int tissuePixels = 0;
            int totalPixels = width * height;
            
            // Pre-calculate threshold (from GPT version - optimization)
            double threshold = 255.0 - glassThreshold;
            
            // Estimate amount of tissue in tile
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double avgIntensity = 0.0;
                    
                    // Calculate average pixel intensity across all channels
                    for (int channel = 0; channel < nbChannels; channel++) {
                        avgIntensity += raster.getSample(x, y, channel);
                    }
                    avgIntensity /= nbChannels;
                    
                    // Check if pixel is tissue or glass/background
                    // Glass/background has high intensity (bright), tissue has low intensity (dark)
                    if (avgIntensity <= threshold) {
                        tissuePixels++;
                    }
                }
            }
            
            return (double) tissuePixels / totalPixels;
            
        } catch (IOException e) {
            logger.error("Error reading tile for tissue detection: {}", tileFile.getName(), e);
            return -1.0;  // Return -1 to indicate error
        }
    }
    
    /**
     * Print processing statistics
     */
    private void printStatistics() {
        logger.info("=" * 60);
        logger.info("TILE GENERATION STATISTICS");
        logger.info("=" * 60);
        logger.info("Total tiles exported: {}", totalTilesExported);
        
        if (enableTissueFiltering) {
            logger.info("Tiles removed (low tissue): {}", tilesRemovedLowTissue);
            logger.info("Tiles kept after filtering: {}", tilesKeptAfterFiltering);
            
            if (totalTilesExported > 0) {
                double keepRate = (double) tilesKeptAfterFiltering / totalTilesExported * 100;
                logger.info("Keep rate: {}%", String.format("%.1f", keepRate));
            }
        } else {
            logger.info("Tissue filtering: DISABLED");
            logger.info("All tiles kept: {}", tilesKeptAfterFiltering);
        }
        
        logger.info("Tiles with annotations: {}", groupedAnnotations.size());
        logger.info("=" * 60);
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
