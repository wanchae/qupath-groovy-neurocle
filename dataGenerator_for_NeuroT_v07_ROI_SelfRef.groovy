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
@Field String outputFolderName = "tumor_dataset"  

// Dataset name (used in JSON filename, subfolder, and ROI annotation Classification matching)
// IMPORTANT: If datasetName matches a className, SELF-REFERENCING mode is activated
// - Example 1: datasetName="ROI", classNames=["Tumor"] → Normal mode (separate ROI and Target)
// - Example 2: datasetName="Negative", classNames=["Negative"] → Self-referencing mode
//   In self-referencing mode, tiles are extracted ONLY from within the specified class annotation
@Field String datasetName = "ROI"

// ================================================================
// MODEL CONFIGURATION
// ================================================================

// Model type: "CLA" (Classification), "OBD" (Object Detection), "SEG" (Segmentation)
@Field String modelType = "SEG"

// Target class names to extract
@Field ArrayList<String> classNames = new ArrayList<>(Arrays.asList("Tumor"))

// ================================================================
// TILE EXTRACTION SETTINGS
// ================================================================

// Downsample factor for tile extraction
@Field double downsample = 4.0

// Tile size in pixels (at the downsampled resolution)
@Field int patchSize = 512

// Overlap between tiles in pixels
@Field int overlap = 32

// Extract only tiles that contain annotations
@Field boolean annotatedTilesOnly = true

// ================================================================
// ROI FILTERING SETTINGS
// ================================================================

// Enable ROI-based filtering (tiles must be completely inside ROI annotation)
// - true: only extract tiles completely inside ROI with matching datasetName Classification
// - false: extract all tiles (original behavior)
// NOTE: In self-referencing mode (datasetName == className), this is automatically enabled
@Field boolean enableROIFiltering = true

// Strict mode: tiles must be entirely within ROI boundary
// - true: reject tiles that touch or cross ROI boundary
// - false: accept tiles that overlap with ROI
@Field boolean strictROIBoundary = true

// ================================================================
// TISSUE FILTERING SETTINGS
// ================================================================

// Enable tissue quality filtering
@Field boolean enableTissueFiltering = true

// Glass/background detection threshold (range: 0-255)
@Field double glassThreshold = 50

// Minimum tissue percentage required to keep a tile (range: 0.0-1.0)
@Field double percentageThreshold = 0.25

// ================================================================
// SCRIPT EXECUTION
// ================================================================

def project = QPEx.getProject()
if (project == null) {
    logger.error("No project is open. Please open a QuPath project first.")
    return
}

def projectBasePath = buildFilePath(PROJECT_BASE_DIR, outputFolderName)
mkdirs(projectBasePath)

ImageData<BufferedImage> imageData = QPEx.getCurrentImageData();

String imageName = imageData.getServer().getMetadata().getName();
logger.info("=" * 60);
logger.info("Processing image: {}", imageName);
logger.info("Target classes: {}", classNames);
logger.info("Dataset name: {}", datasetName);
logger.info("Model type: {}", modelType);
logger.info("Downsample: {}", downsample);
logger.info("Patch size: {}x{}", patchSize, patchSize);

// Check for self-referencing mode
boolean isSelfReferencing = classNames.contains(datasetName);
if (isSelfReferencing) {
    logger.info("Mode: SELF-REFERENCING ('{0}' is both ROI and Target)", datasetName);
    logger.info("  - Tiles will be extracted ONLY from within '{}' annotations", datasetName);
} else {
    logger.info("ROI filtering: {}", enableROIFiltering ? "ENABLED" : "DISABLED");
    if (enableROIFiltering) {
        logger.info("  - ROI annotation classification: {}", datasetName);
        logger.info("  - Strict boundary mode: {}", strictROIBoundary ? "YES" : "NO");
    }
}

logger.info("Tissue filtering: {}", enableTissueFiltering ? "ENABLED" : "DISABLED");
if (enableTissueFiltering) {
    logger.info("  - Glass threshold: {}", glassThreshold);
    logger.info("  - Tissue percentage threshold: {}%", (percentageThreshold * 100));
}
logger.info("=" * 60);

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
    enableROIFiltering: enableROIFiltering,
    strictROIBoundary: strictROIBoundary,
    enableTissueFiltering: enableTissueFiltering,
    glassThreshold: glassThreshold,
    percentageThreshold: percentageThreshold
]

generator = new DataGenerator(config);

try {
    generator.generate();
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
    private boolean enableROIFiltering;
    private boolean strictROIBoundary;
    private boolean enableTissueFiltering;
    private double glassThreshold;
    private double percentageThreshold;
    private Collection<PathObject> selectedAnnos = new ArrayList<>();
    private Collection<PathObject> roiAnnotations = new ArrayList<>();

    private Path dbPath;
    private Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Map<String, TileData> groupedAnnotations = new HashMap<>();
    
    private String imageExtension = ".png";
    
    // Statistics tracking
    private int totalTilesExported = 0;
    private int tilesKeptAfterFiltering = 0;
    private int tilesRemovedLowTissue = 0;
    private int tilesRemovedOutsideROI = 0;

    public DataGenerator(Map<String, Object> config) {
        this.projectBasePath = (String) config.get("projectBasePath");
        this.imageData = (ImageData<BufferedImage>) config.get("imageData");
        this.modelType = (String) config.getOrDefault("modelType", "CLA");
        this.classNames = (ArrayList<String>) config.getOrDefault("classNames", 
            new ArrayList<>(Arrays.asList("Tumor")));
        this.patchSize = (int) config.getOrDefault("patchSize", 512);
        this.overlap = (int) config.getOrDefault("overlap", 0);
        this.datasetName = (String) config.getOrDefault("datasetName", "default");
        this.annotatedTilesOnly = (boolean) config.getOrDefault("annotatedTilesOnly", true);
        this.downsample = (double) config.getOrDefault("downsample", 1.0);
        this.enableROIFiltering = (boolean) config.getOrDefault("enableROIFiltering", false);
        this.strictROIBoundary = (boolean) config.getOrDefault("strictROIBoundary", true);
        this.enableTissueFiltering = (boolean) config.getOrDefault("enableTissueFiltering", true);
        this.glassThreshold = (double) config.getOrDefault("glassThreshold", 50.0);
        this.percentageThreshold = (double) config.getOrDefault("percentageThreshold", 0.25);
        
        Collection<PathObject> allObjects = imageData.getHierarchy().getAnnotationObjects();
        
        // Check if datasetName matches any className (special case)
        boolean isSelfReferencing = classNames.contains(datasetName);
        
        if (isSelfReferencing) {
            logger.info("Self-referencing mode detected: '{}' is both ROI and Target class", datasetName);
            logger.info("Tiles will be extracted only from within '{}' annotations", datasetName);
            
            // In self-referencing mode, the same annotation acts as both ROI and target
            for (PathObject annotation : allObjects) {
                if (annotation.getPathClass() != null) {
                    String className = annotation.getPathClass().getName();
                    if (datasetName.equals(className)) {
                        roiAnnotations.add(annotation);
                        selectedAnnos.add(annotation);
                        logger.info("Found '{}' annotation (ROI + Target): {}", datasetName, annotation.getName());
                    }
                }
            }
            
            // Force enable ROI filtering in self-referencing mode
            if (!roiAnnotations.isEmpty()) {
                enableROIFiltering = true;
                logger.info("ROI filtering automatically enabled for self-referencing mode");
            }
        } else {
            // Normal mode: separate ROI and target classes
            // Find ROI annotations first
            if (enableROIFiltering) {
                for (PathObject annotation : allObjects) {
                    if (annotation.getPathClass() != null) {
                        String className = annotation.getPathClass().getName();
                        if (datasetName.equals(className)) {
                            roiAnnotations.add(annotation);
                            logger.info("Found ROI annotation: {}", annotation.getName());
                        }
                    }
                }
                
                if (roiAnnotations.isEmpty()) {
                    logger.warn("ROI filtering enabled but no ROI annotations found with classification '{}'", datasetName);
                } else {
                    logger.info("Found {} ROI annotation(s)", roiAnnotations.size());
                }
            }
            
            // Find target class annotations (excluding ROI)
            for (PathObject annotation : allObjects) {
                if (annotation.getPathClass() != null) {
                    String pathClassName = annotation.getPathClass().getName();
                    if (classNames.contains(pathClassName) && !datasetName.equals(pathClassName)) {
                        selectedAnnos.add(annotation);
                    }
                }
            }
        }
        
        logger.info("Found {} target annotation(s)", selectedAnnos.size());
    }

    public void generate() throws IOException {
        if (selectedAnnos.isEmpty()) {
            logger.info("No target annotations found. Exiting.");
            return;
        }
        
        createTrainDataset();
        processTiles();
        saveJson();
        printStatistics();
    }

    public void createTrainDataset() throws IOException {
        String fullName = imageData.getServer().getMetadata().getName();
        String baseName = fullName;
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = fullName.substring(0, lastDot);
        }

        // Create output directory
        dbPath = Paths.get(projectBasePath, datasetName, baseName);
        
        try {
            if (!Files.exists(dbPath)) {
                Files.createDirectories(dbPath);
            }
        } catch (Exception e) {
            logger.error("Failed to create directory: {}", dbPath, e);
            throw new IOException("Create directory error", e);
        }

        logger.info("Tile output directory: {}", dbPath.toString());

        // Temporarily remove non-target annotations
        Collection<PathObject> allAnnotations = new ArrayList<>(imageData.getHierarchy().getAnnotationObjects());
        Collection<PathObject> annotationsToRemove = new ArrayList<>();
        
        for (PathObject annotation : allAnnotations) {
            if (annotation.getPathClass() != null) {
                String className = annotation.getPathClass().getName();
                boolean isTargetClass = classNames.contains(className);
                boolean isROIClass = enableROIFiltering && datasetName.equals(className);
                
                if (!isTargetClass && !isROIClass) {
                    annotationsToRemove.add(annotation);
                }
            } else {
                annotationsToRemove.add(annotation);
            }
        }
        
        if (!annotationsToRemove.isEmpty()) {
            logger.info("Temporarily removing {} non-target annotations", annotationsToRemove.size());
            imageData.getHierarchy().removeObjects(annotationsToRemove, true);
        }

        // Configure and run tile exporter
        TileExporter exporter = new TileExporter(imageData);
        exporter.downsample(downsample)
                .imageExtension(imageExtension)
                .tileSize(patchSize)
                .overlap(overlap)
                .includePartialTiles(true)
                .annotatedTilesOnly(annotatedTilesOnly);
        
        try {
            exporter.writeTiles(dbPath.toString());
            logger.info("Tiles exported to: {}", dbPath);
        } catch (IOException e) {
            logger.error("Tile export failed", e);
            throw e;
        } finally {
            if (!annotationsToRemove.isEmpty()) {
                imageData.getHierarchy().addObjects(annotationsToRemove);
                logger.info("Restored {} removed annotations", annotationsToRemove.size());
            }
        }
    }

    public void processTiles() {
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

        String fullName = imageData.getServer().getMetadata().getName();
        String baseName = fullName;
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = fullName.substring(0, lastDot);
        }
        
        for (File tile : tiles) {
            // Skip images subdirectory and non-PNG files
            if (tile.isDirectory()) {
                continue;
            }
            
            String tileName = tile.getName();
            if (!tileName.endsWith(imageExtension)) {
                continue;
            }

            // Parse coordinates from filename
            Pattern pattern = Pattern.compile("\\[.*x=(\\d+),y=(\\d+),w=(\\d+),h=(\\d+)\\]");
            Matcher matcher = pattern.matcher(tileName);
            if (!matcher.find()) {
                logger.warn("Could not parse coordinates: {}", tileName);
                continue;
            }

            int tileX = Integer.parseInt(matcher.group(1));
            int tileY = Integer.parseInt(matcher.group(2));
            int tileWidth = Integer.parseInt(matcher.group(3));
            int tileHeight = Integer.parseInt(matcher.group(4));

            // Create ROI for tile
            ROI tileROI = ROIs.createRectangleROI(tileX, tileY, tileWidth, tileHeight, null);

            // ROI filtering check
            if (enableROIFiltering && !roiAnnotations.isEmpty()) {
                boolean isInsideROI = false;
                
                for (PathObject roiAnnotation : roiAnnotations) {
                    ROI roiGeometry = roiAnnotation.getROI();
                    
                    if (strictROIBoundary) {
                        // Check if all 4 corners are inside ROI
                        boolean allCornersInside = 
                            roiGeometry.contains(tileX, tileY) &&
                            roiGeometry.contains(tileX + tileWidth, tileY) &&
                            roiGeometry.contains(tileX, tileY + tileHeight) &&
                            roiGeometry.contains(tileX + tileWidth, tileY + tileHeight);
                        
                        if (allCornersInside) {
                            isInsideROI = true;
                            break;
                        }
                    } else {
                        // Check if tile overlaps with ROI
                        ROI intersection = RoiTools.intersection(Arrays.asList(roiGeometry, tileROI));
                        if (intersection != null && intersection.getArea() > 0) {
                            isInsideROI = true;
                            break;
                        }
                    }
                }
                
                if (!isInsideROI) {
                    tilesRemovedOutsideROI++;
                    tile.delete();
                    continue;
                }
            }

            // Tissue filtering
            if (enableTissueFiltering) {
                double tissuePercentage = calculateTissuePercentage(tile);
                
                if (tissuePercentage >= 0 && tissuePercentage < percentageThreshold) {
                    tilesRemovedLowTissue++;
                    tile.delete();
                    continue;
                }
            }

            // Process annotations within tile
            List<RegionLabel> regionLabels = new ArrayList<>();
            String tileClassLabel = "";
            Map<String, Double> classAreaMap = new HashMap<>();

            for (PathObject annotation : selectedAnnos) {
                ROI roi = annotation.getROI();
                ROI intersectionROI = RoiTools.intersection(Arrays.asList(roi, tileROI));
                
                if (intersectionROI == null || intersectionROI.getArea() <= 0.0) {
                    continue;
                }

                PathClass pathClass = annotation.getPathClass();
                String className = pathClass != null ? pathClass.getName() : "Unknown";

                // Process based on model type
                switch (modelType) {
                    case "OBD":
                        regionLabels.add(new RegionLabel(className, "rectangle",
                                (int)((intersectionROI.getBoundsX() - tileX) / downsample),
                                (int)((intersectionROI.getBoundsY() - tileY) / downsample),
                                (int)(intersectionROI.getBoundsWidth() / downsample),
                                (int)(intersectionROI.getBoundsHeight() / downsample)));
                        break;
                    case "SEG":
                        List<List<Integer>> newPoints = new ArrayList<>();
                        for (Point2 pt : intersectionROI.getAllPoints()) {
                            newPoints.add(Arrays.asList(
                                    (int)((pt.getX() - tileX) / downsample),
                                    (int)((pt.getY() - tileY) / downsample)));
                        }
                        regionLabels.add(new RegionLabel(className, "polygon", newPoints));
                        break;
                    case "CLA":
                        double area = intersectionROI.getArea();
                        classAreaMap.put(className, classAreaMap.getOrDefault(className, 0.0) + area);
                        break;
                }
            }

            // For classification, find dominant class
            if ("CLA".equals(modelType) && !classAreaMap.isEmpty()) {
                tileClassLabel = classAreaMap.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("");
            }

            // Create TileData
            String fileName = baseName + "_" + tileName;
            String set = regionLabels.isEmpty() && tileClassLabel.isEmpty() ? "unlabeled" : "labeled";
            String classLabel = tileClassLabel.isEmpty() ? (regionLabels.isEmpty() ? "Normal" : regionLabels.get(0).className) : tileClassLabel;

            TileData tileData = new TileData(fileName, set, classLabel, tileWidth, tileHeight);
            tileData.regionLabel.addAll(regionLabels);
            groupedAnnotations.put(fileName, tileData);

            // Move tile to images directory
            File destFile = new File(processedDir, tile.getName());
            try {
                Files.move(tile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tilesKeptAfterFiltering++;
            } catch (IOException e) {
                logger.error("Failed to move file: {}", tile.getName(), e);
            }
        }
        
        logger.info("Tile processing completed: {} tiles kept", tilesKeptAfterFiltering);
    }
    
    private double calculateTissuePercentage(File tileFile) {
        try {
            BufferedImage image = ImageIO.read(tileFile);
            if (image == null) {
                logger.warn("Failed to read image: {}", tileFile.getName());
                return -1.0;
            }
            
            Raster raster = image.getRaster();
            int width = image.getWidth();
            int height = image.getHeight();
            int nbChannels = raster.getNumBands();
            
            int tissuePixels = 0;
            int totalPixels = width * height;
            double threshold = 255.0 - glassThreshold;
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double avgIntensity = 0.0;
                    for (int channel = 0; channel < nbChannels; channel++) {
                        avgIntensity += raster.getSample(x, y, channel);
                    }
                    avgIntensity /= nbChannels;
                    
                    if (avgIntensity <= threshold) {
                        tissuePixels++;
                    }
                }
            }
            
            return (double) tissuePixels / totalPixels;
            
        } catch (IOException e) {
            logger.error("Error reading tile: {}", tileFile.getName(), e);
            return -1.0;
        }
    }
    
    private void printStatistics() {
        logger.info("=" * 60);
        logger.info("TILE GENERATION STATISTICS");
        logger.info("=" * 60);
        logger.info("Total tiles exported: {}", totalTilesExported);
        
        if (enableROIFiltering) {
            logger.info("Tiles removed (outside ROI): {}", tilesRemovedOutsideROI);
        }
        
        if (enableTissueFiltering) {
            logger.info("Tiles removed (low tissue): {}", tilesRemovedLowTissue);
        }
        
        logger.info("Tiles kept after filtering: {}", tilesKeptAfterFiltering);
        
        if (totalTilesExported > 0) {
            double keepRate = (double) tilesKeptAfterFiltering / totalTilesExported * 100;
            logger.info("Keep rate: {}%", String.format("%.1f", keepRate));
        }
        
        logger.info("Tiles with annotations: {}", groupedAnnotations.size());
        logger.info("=" * 60);
    }
    
    public void saveJson() throws IOException {
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
    	
    	String jsonFileName = projectName + "_" + datasetName + "_output.json";
    	File outputFile = dbPath.resolve(jsonFileName).toFile();
    	
    	logger.info("Creating JSON output file: {}", jsonFileName);
    	
        Map<String, TileData> existingAnnotations = new HashMap<>();

        // Load existing data
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

        // Merge with new data
        existingAnnotations.putAll(groupedAnnotations);
        
        // Create final JSON
        Map<String, Object> finalJson = new HashMap<>();
        finalJson.put("label_type", modelType);
        finalJson.put("source", "labelset");
        finalJson.put("data", new ArrayList<>(existingAnnotations.values()));

        // Write JSON
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(gson.toJson(finalJson));
            logger.info("JSON file saved: {}", outputFile.getAbsolutePath());
        }
    }

    class RegionLabel {
        String className;
        String type;
        List<List<Integer>> points;
        int x, y, width, height;
        
        public RegionLabel() {
        }

        public RegionLabel(String className, String type, int x, int y, int width, int height) {
            this.className = className;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.points = null;  
        }

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
