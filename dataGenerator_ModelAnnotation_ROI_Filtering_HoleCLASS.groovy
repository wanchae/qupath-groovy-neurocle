import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
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
import java.awt.Shape;
import java.awt.geom.PathIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.transform.Field

import java.awt.Color;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;

// ================================================================
// DATA GENERATOR FOR MODEL-GENERATED ANNOTATIONS
// ================================================================
// 
// PURPOSE:
// This script extracts training tiles from images with model-generated annotations.
// Designed for fine-tuning workflows where you need to select specific regions.
//
// USE CASES:
// 
// 1. BACKGROUND/NEGATIVE TILES (Self-referencing mode)
//    Extract tiles from within a specific region as background data.
//    Example:
//      @Field String datasetName = "Background"
//      @Field ArrayList<String> classNames = ["Background"]
//    Result: Extracts all tiles within "Background" annotations
//
// 2. ROI-BASED TARGET EXTRACTION
//    Extract specific classes only from ROI-selected areas.
//    Example:
//      @Field String datasetName = "ROI"
//      @Field ArrayList<String> classNames = ["Tumor", "Stroma"]
//    Result: Extracts Tumor and Stroma tiles only from within ROI annotations
//
// 3. PYRAMID LEVEL CONFIGURATION
//    Check your image pyramid levels and set downsample accordingly.
//    Pyramid levels [1, 4, 16] correspond to levels [0, 1, 2]
//    Example:
//      @Field double downsample = 4.0  // For pyramid level 1
//      @Field int patchSize = 512      // Tile size at downsampled resolution
//      @Field int overlap = 32         // Overlap between tiles
//      @Field boolean annotatedTilesOnly = true
//
// ================================================================

// ================================================================
// DATA GENERATOR FOR MODEL-GENERATED ANNOTATIONS (Hole - asign a class)
// ================================================================

// 1) outputFolderName: Output folder name (created under QuPath project directory)
// 2) datasetName: Dataset name - used for folder structure and JSON filename
//  # Also used as ROI classification name when enableROIFiltering is true
// 3) odelType: Model type: "CLA" (Classification), "OBD" (Object Detection), "SEG" (Segmentation)
// 4) Target class names to extract
//  # If datasetName == className, operates in self-referencing mode

@Field String outputFolderName = "tumor_dataset_version"  
@Field String datasetName = "ROI"
@Field String modelType = "SEG"
@Field ArrayList<String> classNames = new ArrayList<>(Arrays.asList("ADE","Gland"))


// ================================================================
// SETTINGS
// ================================================================

// 1) downsample: Downsample factor - must match your pyramid level
//  # Example: Pyramid [1, 4, 16] → Use downsample [1.0, 4.0, 16.0]
// 2) patchSize: Tile size in pixels (at the downsampled resolution)
// 3) Overlap between adjacent tiles in pixels
// 4) annotatedTilesOnly: Extract only tiles that contain annotations
// 5) flipYCoordinates: Y-coordinate flip option
//  # Set to true if your annotations appear upside-down after import

@Field double downsample = 4.0
@Field int patchSize = 512
@Field int overlap = 32

@Field boolean annotatedTilesOnly = true
@Field boolean flipYCoordinates = false 

// ================================================================
// FILTERING SETTINGS
// ================================================================

// 1) Enable ROI-based filtering
//  # When true, only tiles within ROI annotations are extracted
//  # ROI annotations must have Classification matching datasetName
// 2) Strict boundary mode
//  # true: Exclude tiles that touch ROI boundary
//  # false: Include tiles that overlap with ROI

@Field boolean enableROIFiltering = true
@Field boolean strictROIBoundary = true

// ================================================================
// TISSUE QUALITY FILTERING SETTINGS
// ================================================================

// 1) Enable tissue quality filtering to remove background/glass regions
// 2) glassThreshold: Background detection threshold (0-255)
//  # Higher values detect more background
// 3) percentageThreshold: Minimum tissue percentage required to keep a tile (0.0-1.0)
//  # Tiles with less tissue are discarded

@Field boolean enableTissueFiltering = true
@Field double glassThreshold = 50
@Field double percentageThreshold = 0.1

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

logger.info("=" * 60);
logger.info("Processing image: {}", imageData.getServer().getMetadata().getName());
logger.info("Settings - Flip Y: {}", flipYCoordinates);
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
    percentageThreshold: percentageThreshold,
    flipYCoordinates: flipYCoordinates // 추가된 옵션 전달
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
    private boolean flipYCoordinates; // 상하 반전 변수

    private Collection<PathObject> selectedAnnos = new ArrayList<>();
    private Collection<PathObject> roiAnnotations = new ArrayList<>();

    private Path dbPath;
    private Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Map<String, TileData> groupedAnnotations = new HashMap<>();
    
    private String imageExtension = ".png";
    private int totalTilesExported = 0;
    private int tilesKeptAfterFiltering = 0;
    private int tilesRemovedLowTissue = 0;
    private int tilesRemovedOutsideROI = 0;

    public DataGenerator(Map<String, Object> config) {
        this.projectBasePath = (String) config.get("projectBasePath");
        this.imageData = (ImageData<BufferedImage>) config.get("imageData");
        this.modelType = (String) config.getOrDefault("modelType", "CLA");
        this.classNames = (ArrayList<String>) config.getOrDefault("classNames", new ArrayList<>(Arrays.asList("Tumor")));
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
        this.flipYCoordinates = (boolean) config.getOrDefault("flipYCoordinates", false);
        
        this.dbPath = Paths.get(projectBasePath, datasetName);
        Collection<PathObject> allObjects = imageData.getHierarchy().getAnnotationObjects();
        
        // Self-referencing check
        boolean isSelfReferencing = classNames.contains(datasetName);
        if (isSelfReferencing) {
            for (PathObject annotation : allObjects) {
                if (annotation.getPathClass() != null && datasetName.equals(annotation.getPathClass().getName())) {
                    roiAnnotations.add(annotation);
                    selectedAnnos.add(annotation);
                }
            }
            if (!roiAnnotations.isEmpty()) enableROIFiltering = true;
        } else {
            if (enableROIFiltering) {
                for (PathObject annotation : allObjects) {
                    if (annotation.getPathClass() != null && datasetName.equals(annotation.getPathClass().getName())) {
                        roiAnnotations.add(annotation);
                    }
                }
            }
            for (PathObject annotation : allObjects) {
                if (annotation.getPathClass() != null) {
                    String pathClassName = annotation.getPathClass().getName();
                    if (classNames.contains(pathClassName) && !datasetName.equals(pathClassName)) {
                        selectedAnnos.add(annotation);
                    }
                }
            }
        }
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
        dbPath = Paths.get(projectBasePath, datasetName);
        if (!Files.exists(dbPath)) Files.createDirectories(dbPath);

        // Temporarily remove non-target annotations to avoid exporting them in tiles
        Collection<PathObject> allAnnotations = new ArrayList<>(imageData.getHierarchy().getAnnotationObjects());
        Collection<PathObject> annotationsToRemove = new ArrayList<>();
        
        for (PathObject annotation : allAnnotations) {
            if (annotation.getPathClass() != null) {
                String className = annotation.getPathClass().getName();
                boolean isTargetClass = classNames.contains(className);
                boolean isROIClass = enableROIFiltering && datasetName.equals(className);
                if (!isTargetClass && !isROIClass) annotationsToRemove.add(annotation);
            } else {
                annotationsToRemove.add(annotation);
            }
        }
        
        if (!annotationsToRemove.isEmpty()) imageData.getHierarchy().removeObjects(annotationsToRemove, true);

        TileExporter exporter = new TileExporter(imageData);
        exporter.downsample(downsample)
                .imageExtension(imageExtension)
                .tileSize(patchSize)
                .overlap(overlap)
                .includePartialTiles(true)
                .annotatedTilesOnly(annotatedTilesOnly);
        try {
            exporter.writeTiles(dbPath.toString());
        } catch (IOException e) {
            throw e;
        } finally {
            if (!annotationsToRemove.isEmpty()) imageData.getHierarchy().addObjects(annotationsToRemove);
        }
    }

    public void processTiles() {
        File processedDir = new File(dbPath.toString(), "images");
        if (!processedDir.exists()) processedDir.mkdirs();

        File[] tiles = dbPath.toFile().listFiles();
        if (tiles == null) return;

        totalTilesExported = tiles.length;

        for (File tile : tiles) {
            if (tile.isDirectory() || !tile.getName().endsWith(imageExtension)) continue;
            
            String tileName = tile.getName();
            Pattern pattern = Pattern.compile("\\[.*x=(\\d+),y=(\\d+),w=(\\d+),h=(\\d+)\\]");
            Matcher matcher = pattern.matcher(tileName);
            
            if (!matcher.find()) continue;

            int tileX = Integer.parseInt(matcher.group(1));
            int tileY = Integer.parseInt(matcher.group(2));
            int tileWidth = Integer.parseInt(matcher.group(3));
            int tileHeight = Integer.parseInt(matcher.group(4));
            
            ROI tileROI = ROIs.createRectangleROI(tileX, tileY, tileWidth, tileHeight, null);

            // 1. ROI Filtering
            if (enableROIFiltering && !roiAnnotations.isEmpty()) {
                boolean isInsideROI = false;
                for (PathObject roiAnnotation : roiAnnotations) {
                    ROI roiGeometry = roiAnnotation.getROI();
                    if (strictROIBoundary) {
                        if (roiGeometry.contains(tileX, tileY) && 
                            roiGeometry.contains(tileX + tileWidth, tileY + tileHeight)) { // Check diagonal corners enough for rect
                            isInsideROI = true; break;
                        }
                    } else {
                        ROI intersection = RoiTools.intersection(Arrays.asList(roiGeometry, tileROI));
                        if (intersection != null && intersection.getArea() > 0) {
                            isInsideROI = true; break;
                        }
                    }
                }
                if (!isInsideROI) {
                    tilesRemovedOutsideROI++;
                    tile.delete();
                    continue;
                }
            }

            // 2. Tissue Filtering
            if (enableTissueFiltering) {
                double tissuePercentage = calculateTissuePercentage(tile);
                if (tissuePercentage >= 0 && tissuePercentage < percentageThreshold) {
                    tilesRemovedLowTissue++;
                    tile.delete();
                    continue;
                }
            }

            // 3. Process Annotations (Core Logic Changed Here)
            List<RegionLabel> regionLabels = new ArrayList<>();
            String tileClassLabel = "";
            Map<String, Double> classAreaMap = new HashMap<>();

            for (PathObject annotation : selectedAnnos) {
                ROI roi = annotation.getROI();
                ROI intersectionROI = RoiTools.intersection(Arrays.asList(roi, tileROI));
                
                if (intersectionROI == null || intersectionROI.isEmpty()) continue;

                String className = annotation.getPathClass() != null ? annotation.getPathClass().getName() : "Unknown";

                if (modelType.equals("SEG")) {
                    // *** FIX: Use PathIterator directly to separate shapes and avoid bridges ***
                    Shape shape = intersectionROI.getShape();
                    // flatness 0.5 allows for high precision (no straight lines on curves)
                    PathIterator pi = shape.getPathIterator(null, 0.5); 
                    
                    List<List<Integer>> currentPolyPoints = new ArrayList<>();
                    double[] coords = new double[6];
                    
                    while (!pi.isDone()) {
                        int type = pi.currentSegment(coords);
                        
                        // Coordinate transform
                        int x = (int)((coords[0] - tileX) / downsample);
                        int y = (int)((coords[1] - tileY) / downsample);
                        
                        // Handle Y-Flip if requested
                        if (flipYCoordinates) {
                            y = (int)(patchSize - y);
                        }
                        
                        // Clamp coordinates to tile boundary [0, patchSize]
                        // (Optional, but good for safety)
                        x = Math.max(0, Math.min(patchSize, x));
                        y = Math.max(0, Math.min(patchSize, y));

                        if (type == PathIterator.SEG_MOVETO) {
                            // Start of a new polygon (island)
                            // If we were building one, save it first
                            if (!currentPolyPoints.isEmpty()) {
                                if (currentPolyPoints.size() > 2) {
                                    regionLabels.add(new RegionLabel(className, "PolyGon", new ArrayList<>(currentPolyPoints)));
                                }
                                currentPolyPoints.clear();
                            }
                            currentPolyPoints.add(Arrays.asList(x, y));
                        } 
                        else if (type == PathIterator.SEG_LINETO) {
                            currentPolyPoints.add(Arrays.asList(x, y));
                        } 
                        else if (type == PathIterator.SEG_CLOSE) {
                            // End of loop. Save the polygon.
                            if (!currentPolyPoints.isEmpty() && currentPolyPoints.size() > 2) {
                                regionLabels.add(new RegionLabel(className, "PolyGon", new ArrayList<>(currentPolyPoints)));
                            }
                            currentPolyPoints.clear();
                        }
                        pi.next();
                    }
                    
                    // Catch any trailing polygon not closed by SEG_CLOSE
                    if (!currentPolyPoints.isEmpty() && currentPolyPoints.size() > 2) {
                        regionLabels.add(new RegionLabel(className, "PolyGon", new ArrayList<>(currentPolyPoints)));
                    }

                } else if (modelType.equals("CLA")) {
                     double area = intersectionROI.getArea();
                     classAreaMap.put(className, classAreaMap.getOrDefault(className, 0.0) + area);
                }
            }

            if ("CLA".equals(modelType) && !classAreaMap.isEmpty()) {
                tileClassLabel = classAreaMap.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("");
            }

            String fileName = tileName.split(Pattern.quote(imageExtension))[0] + ".png";
            
            // Only add if we found regions or if it's classification
            if (!regionLabels.isEmpty() || "CLA".equals(modelType)) {
                groupedAnnotations.computeIfAbsent(fileName, k ->
                    new TileData(fileName, modelType, "", patchSize, patchSize)
                );
                if ("CLA".equals(modelType)) {
                    groupedAnnotations.get(fileName).classLabel = tileClassLabel;
                } else {
                    groupedAnnotations.get(fileName).regionLabel.addAll(regionLabels);
                }
            }

            File destFile = new File(processedDir, tile.getName());
            try {
                Files.move(tile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tilesKeptAfterFiltering++;
            } catch (IOException e) {
                logger.error("Failed to move file", e);
            }
        }
    }
    
    // ... (Utility methods: calculateTissuePercentage, printStatistics same as before) ...
    // Note: Removed simplifyPolygon methods as we now use PathIterator for better precision
    
    private double calculateTissuePercentage(File tileFile) {
        try {
            BufferedImage image = ImageIO.read(tileFile);
            if (image == null) return -1.0;
            Raster raster = image.getRaster();
            int width = image.getWidth();
            int height = image.getHeight();
            int nbChannels = raster.getNumBands();
            int tissuePixels = 0;
            double threshold = 255.0 - glassThreshold;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double avgIntensity = 0.0;
                    for (int channel = 0; channel < nbChannels; channel++) avgIntensity += raster.getSample(x, y, channel);
                    if ((avgIntensity / nbChannels) <= threshold) tissuePixels++;
                }
            }
            return (double) tissuePixels / (width * height);
        } catch (IOException e) { return -1.0; }
    }

    private void printStatistics() {
        logger.info("=" * 60);
        logger.info("TILE GENERATION STATISTICS");
        logger.info("Total tiles exported: {}", totalTilesExported);
        logger.info("Tiles kept: {}", tilesKeptAfterFiltering);
        logger.info("Annotations Generated: {}", groupedAnnotations.size());
        logger.info("=" * 60);
    }
    
    public void saveJson() throws IOException {
        if (groupedAnnotations.isEmpty()) return;
        
        String projectName = QPEx.getProject().getPath().getParent().getFileName().toString();
        String jsonFileName = projectName + "_" + datasetName + "_output.json";
        File outputFile = dbPath.resolve(jsonFileName).toFile();
        
        Map<String, TileData> existingAnnotations = new HashMap<>();
        if (outputFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(outputFile))) {
                Map<?, ?> parsedJson = gson.fromJson(reader, Map.class);
                List<?> existingData = (List<?>) parsedJson.get("data");
                if (existingData != null) {
                    for (Object tileObj : existingData) {
                        Map<?, ?> tileMap = (Map<?, ?>) tileObj;
                        TileData tileData = gson.fromJson(gson.toJson(tileMap), TileData.class);
                        existingAnnotations.put(tileData.fileName, tileData);
                    }
                }
            } catch (Exception e) { logger.error("Error reading JSON", e); }
        }

        existingAnnotations.putAll(groupedAnnotations);
        
        Map<String, Object> finalJson = new HashMap<>();
        finalJson.put("label_type", modelType);
        finalJson.put("source", "labelset");
        finalJson.put("data", new ArrayList<>(existingAnnotations.values()));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(gson.toJson(finalJson));
            logger.info("JSON file saved: {}", outputFile.getAbsolutePath());
        }
    }

    // Data Classes
    class RegionLabel {
        String className;
        String type;
        List<List<Integer>> points;
        int x, y, width, height;
        
        public RegionLabel(String className, String type, List<List<Integer>> points) {
            this.className = className;
            this.type = type;
            this.points = points;
        }
        public RegionLabel(String className, String type, int x, int y, int width, int height) {
            this.className = className;
            this.type = type;
            this.x = x; this.y = y; this.width = width; this.height = height;
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

        public TileData(String fileName, String set, String classLabel, int width, int height) {
            this.fileName = fileName;
            this.set = set;
            this.classLabel = classLabel;
            this.width = width;
            this.height = height;
        }
    }
}