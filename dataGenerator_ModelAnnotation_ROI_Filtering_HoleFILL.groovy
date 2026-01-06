import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import qupath.lib.geom.Point2;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.writers.TileExporter;
import qupath.lib.objects.PathObject;
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

// *** JTS Imports (Artifact Remover Edition) ***
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.locationtech.jts.geom.util.PolygonExtracter; // Tool for seperate objects

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.transform.Field
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
// DATA GENERATOR: v15 ARTIFACT REMOVER (No Lines, FILL HOLES)
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
logger.info("Mode: v15 Artifact Remover (Exterior Rings Only, Forced Separation)");
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
    flipYCoordinates: flipYCoordinates
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
    private boolean flipYCoordinates;
    
    private GeometryFactory geometryFactory = new GeometryFactory();

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
        
        // 1. Identify ROI Annotations
        if (enableROIFiltering || classNames.contains(datasetName)) {
             for (PathObject annotation : allObjects) {
                if (annotation.getPathClass() != null && datasetName.equals(annotation.getPathClass().getName())) {
                    roiAnnotations.add(annotation);
                    if (classNames.contains(datasetName)) selectedAnnos.add(annotation);
                }
            }
            if (!roiAnnotations.isEmpty()) enableROIFiltering = true;
        }

        // 2. Identify Target Annotations
        for (PathObject annotation : allObjects) {
            if (annotation.getPathClass() != null) {
                String pathClassName = annotation.getPathClass().getName();
                if (classNames.contains(pathClassName)) {
                    if (!selectedAnnos.contains(annotation)) selectedAnnos.add(annotation);
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
        
        // Precision Reducer (Robustness)
        PrecisionModel pm = new PrecisionModel(100.0); 
        GeometryPrecisionReducer reducer = new GeometryPrecisionReducer(pm);

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
            
            Coordinate[] tileCoords = new Coordinate[5];
            tileCoords[0] = new Coordinate(tileX, tileY);
            tileCoords[1] = new Coordinate(tileX + tileWidth, tileY);
            tileCoords[2] = new Coordinate(tileX + tileWidth, tileY + tileHeight);
            tileCoords[3] = new Coordinate(tileX, tileY + tileHeight);
            tileCoords[4] = new Coordinate(tileX, tileY);
            Geometry tileGeom = geometryFactory.createPolygon(tileCoords);

            // 1. ROI Filtering
            if (enableROIFiltering && !roiAnnotations.isEmpty()) {
                boolean isInsideROI = false;
                for (PathObject roiAnnotation : roiAnnotations) {
                     Geometry roiGeom = roiAnnotation.getROI().getGeometry();
                     if (roiGeom == null) continue;
                     
                     if (strictROIBoundary) {
                         if (roiGeom.contains(tileGeom) || roiGeom.covers(tileGeom)) {
                             isInsideROI = true; break;
                         }
                         try {
                             if (roiGeom.intersection(tileGeom).getArea() >= (tileGeom.getArea() * 0.99)) {
                                  isInsideROI = true; break;
                             }
                         } catch (Exception e) {}
                     } else {
                         if (roiGeom.intersects(tileGeom)) {
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

            // 3. Process Annotations
            List<RegionLabel> regionLabels = new ArrayList<>();
            String tileClassLabel = "";
            Map<String, Double> classAreaMap = new HashMap<>();

            for (PathObject annotation : selectedAnnos) {
                Geometry annoGeom = annotation.getROI().getGeometry();
                if (annoGeom == null) continue;
                if (!annoGeom.intersects(tileGeom)) continue;

                Geometry validAnno = annoGeom.isValid() ? annoGeom : annoGeom.buffer(0);
                Geometry intersectionGeom = null;
                
                try {
                    intersectionGeom = validAnno.intersection(tileGeom);
                } catch (Exception e) {
                    try { intersectionGeom = validAnno.buffer(0).intersection(tileGeom.buffer(0)); } catch (Exception e2) { continue; }
                }

                if (intersectionGeom == null || intersectionGeom.isEmpty()) continue;

                String className = annotation.getPathClass() != null ? annotation.getPathClass().getName() : "Unknown";

                if (modelType.equals("SEG")) {
                    // *** V15 ARTIFACT REMOVER START ***
                    // 1. Topology Clean & Snap
                    intersectionGeom = reducer.reduce(intersectionGeom);
                    if (!intersectionGeom.isValid()) intersectionGeom = intersectionGeom.buffer(0);
                    
                    // 2. FORCE EXPLODE: Use PolygonExtracter to flatten ALL hierarchy
                    // This absolutely guarantees that each island is a separate list entry.
                    List<Polygon> atomicPolygons = new ArrayList<>();
                    PolygonExtracter.getPolygons(intersectionGeom, atomicPolygons);

                    for (Polygon poly : atomicPolygons) {
                        // 3. EXTERIOR RING ONLY: Eliminate Holes & Bridge Lines
                        // By getting only the exterior ring, we remove the internal "cut" lines
                        // that JTS creates to connect holes. 
                        // Result: Solid polygons (holes filled) but ZERO LINE ARTIFACTS.
                        Coordinate[] coords = poly.getExteriorRing().getCoordinates();
                        
                        if (coords.length < 4) continue; 

                        List<List<Integer>> polyPoints = new ArrayList<>();
                        for (Coordinate c : coords) {
                            int px = (int) Math.round((c.x - tileX) / downsample);
                            int py = (int) Math.round((c.y - tileY) / downsample);
                            
                            if (flipYCoordinates) py = patchSize - py;
                            
                            px = Math.max(0, Math.min(patchSize, px));
                            py = Math.max(0, Math.min(patchSize, py));
                            
                            polyPoints.add(Arrays.asList(px, py));
                        }
                        
                        if (polyPoints.size() > 2) {
                            regionLabels.add(new RegionLabel(className, "PolyGon", polyPoints));
                        }
                    }
                    // *** V15 ARTIFACT REMOVER END ***

                } else if (modelType.equals("CLA")) {
                     double area = intersectionGeom.getArea();
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