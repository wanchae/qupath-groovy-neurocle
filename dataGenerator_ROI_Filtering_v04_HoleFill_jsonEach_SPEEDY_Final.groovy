import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import qupath.lib.common.ColorTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.regions.RegionRequest;

// JTS Geometry (Used for stable outline extraction)
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Coordinate;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import groovy.transform.Field
import javax.imageio.ImageIO;

// ================================================================
// DATA GENERATOR (Solid Polygons / Fill Holes)
// ================================================================
// 1) outputFolderName: Output folder name (created under QuPath project directory)
// 2) datasetName: Dataset name - used for folder structure and JSON filename
//  # Also used as ROI classification name when enableROIFiltering is true
// 3) odelType: Model type: "CLA" (Classification), "OBD" (Object Detection), "SEG" (Segmentation)
// 4) Target class names to extract
//  # If datasetName == className, operates in self-referencing mode

@Field String outputFolderName = "tumor_dataset_AOV_ST_COL_v01_ROI2_Solid_0002"  
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

@Field double downsample = 4.0
@Field int patchSize = 512
@Field int overlap = 32
@Field boolean annotatedTilesOnly = true

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
    private ImageServer<BufferedImage> server;
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
    
    // JSON Size Optimization (Removing Whitespace)
    private Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    
    private Map<String, TileData> groupedAnnotations = new ConcurrentHashMap<>();
    
    private String imageExtension = ".png";
    
    private AtomicInteger totalTilesProcessed = new AtomicInteger(0);
    private AtomicInteger tilesSaved = new AtomicInteger(0);

    private int[] tissueMaskPixels;
    private int maskWidth;
    private int maskHeight;
    private double maskScaleFactor;

    public DataGenerator(Map<String, Object> config) {
        this.projectBasePath = (String) config.get("projectBasePath");
        this.imageData = (ImageData<BufferedImage>) config.get("imageData");
        this.server = imageData.getServer();
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
        
        this.dbPath = Paths.get(projectBasePath, datasetName);
        Collection<PathObject> allObjects = imageData.getHierarchy().getAnnotationObjects();
        
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
        if (enableROIFiltering && roiAnnotations.isEmpty()) {
            logger.warn("SKIPPING IMAGE: No annotations found for ROI class '{}'.", datasetName);
            return;
        }

        if (selectedAnnos.isEmpty()) {
            logger.info("No target annotations found (Target Classes: {}). Exiting.", classNames);
            return;
        }
        
        if (enableTissueFiltering) {
            prepareTissueMask();
        }

        exportSparseTiles();
        
        saveJson();
        printStatistics();
    }
    
    private void prepareTissueMask() {
        try {
            double maxDim = Math.max(server.getWidth(), server.getHeight());
            double targetDim = 2048.0;
            double requestDownsample = Math.max(1.0, maxDim / targetDim);
            requestDownsample = Math.min(requestDownsample, 64.0); 

            RegionRequest request = RegionRequest.createInstance(server.getPath(), requestDownsample, 0, 0, server.getWidth(), server.getHeight());
            BufferedImage img = server.readRegion(request);
            
            this.maskWidth = img.getWidth();
            this.maskHeight = img.getHeight();
            this.maskScaleFactor = 1.0 / requestDownsample; 
            this.tissueMaskPixels = img.getRGB(0, 0, maskWidth, maskHeight, null, 0, maskWidth);
            
            logger.info("Tissue Mask Prepared. Size: {}x{}, Scale: {}", maskWidth, maskHeight, maskScaleFactor);
        } catch (Exception e) {
            logger.error("Failed to prepare tissue mask.", e);
            this.enableTissueFiltering = false;
        }
    }

    private void exportSparseTiles() {
        File processedDir = new File(dbPath.toString(), "images");
        if (!processedDir.exists()) processedDir.mkdirs();
        
        int serverWidth = server.getWidth();
        int serverHeight = server.getHeight();
        
        double regionSize = patchSize * downsample;
        double stepSize = (patchSize - overlap) * downsample;
        
        List<Point2> potentialTiles = new ArrayList<>();
        
        for (double y = 0; y + regionSize <= serverHeight; y += stepSize) {
            for (double x = 0; x + regionSize <= serverWidth; x += stepSize) {
                potentialTiles.add(new Point2(x, y));
            }
        }
        
        totalTilesProcessed.set(potentialTiles.size());
        
        potentialTiles.parallelStream().forEach(point -> {
            int x = (int) point.getX();
            int y = (int) point.getY();
            int w = (int) regionSize;
            int h = (int) regionSize;
            
            try {
                processSingleTile(x, y, w, h, processedDir);
            } catch (Exception e) {
                logger.error("Error processing tile at " + x + "," + y, e);
            }
        });
    }

    private void processSingleTile(int x, int y, int w, int h, File processedDir) {
        if (enableTissueFiltering && !checkTissueInMask(x, y, w, h)) return;
        
        ROI tileROI = ROIs.createRectangleROI(x, y, w, h, null);
        
        // ROI Filtering
        if (enableROIFiltering) {
            boolean isInsideROI = false;
            for (PathObject roiAnnotation : roiAnnotations) {
                ROI roiGeometry = roiAnnotation.getROI();
                if (roiGeometry == null || !roiGeometry.isArea()) continue;

                if (!roiGeometry.getShape().getBounds().intersects(x, y, w, h)) continue;

                if (strictROIBoundary) {
                    if (roiGeometry.contains(x, y) && roiGeometry.contains(x + w, y + h)) {
                        isInsideROI = true; break;
                    }
                } else {
                    if (RoiTools.intersection(Arrays.asList(roiGeometry, tileROI)).getArea() > 0) {
                        isInsideROI = true; break;
                    }
                }
            }
            if (!isInsideROI) return;
        }
        
        List<RegionLabel> regionLabels = new ArrayList<>();
        Map<String, Double> classAreaMap = new HashMap<>();
        boolean hasTargetAnnotation = false;
        
        for (PathObject annotation : selectedAnnos) {
            ROI roi = annotation.getROI();
            if (roi == null || !roi.isArea()) continue;
            
            if (!roi.getShape().getBounds().intersects(x, y, w, h)) continue;
            
            ROI intersectionROI = RoiTools.intersection(Arrays.asList(roi, tileROI));
            if (intersectionROI == null || intersectionROI.isEmpty() || !intersectionROI.isArea()) continue;
            
            hasTargetAnnotation = true;
            String className = annotation.getPathClass() != null ? annotation.getPathClass().getName() : "Unknown";

            if (modelType.equals("SEG")) {
                try {
                    // Boldly disregard hole information and store only the exterior cleanly
                    Geometry geom = intersectionROI.getGeometry();
                    
                    if (!geom.isValid()) {
                        geom = geom.buffer(0);
                    }
                    
                    extractFilledPolygons(geom, x, y, className, regionLabels);
                    
                } catch (Exception e) {
                    logger.error("Error extracting geometry for tile " + x + "," + y, e);
                }
            } else if (modelType.equals("CLA")) {
                 classAreaMap.put(className, classAreaMap.getOrDefault(className, 0.0) + intersectionROI.getArea());
            }
        }

        if (annotatedTilesOnly && !hasTargetAnnotation) return;

        String imageName = server.getMetadata().getName();
        String tileName = String.format("%s [d=%.0f,x=%d,y=%d,w=%d,h=%d]%s", 
                                        imageName.substring(0, imageName.lastIndexOf('.')), 
                                        downsample, x, y, w, h, imageExtension);
        String saveName = tileName.split(Pattern.quote(imageExtension))[0] + ".png";

        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, x, y, w, h);
        try {
            BufferedImage tileImage = server.readRegion(request);
            File outputFile = new File(processedDir, saveName);
            ImageIO.write(tileImage, "png", outputFile);
            tilesSaved.incrementAndGet();
            
            String tileClassLabel = "";
            if ("CLA".equals(modelType) && !classAreaMap.isEmpty()) {
                tileClassLabel = classAreaMap.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse("");
            }
            
            if (!regionLabels.isEmpty() || "CLA".equals(modelType)) {
                groupedAnnotations.computeIfAbsent(saveName, k -> new TileData(saveName, modelType, "", patchSize, patchSize));
                if ("CLA".equals(modelType)) groupedAnnotations.get(saveName).classLabel = tileClassLabel;
                else groupedAnnotations.get(saveName).regionLabel.addAll(regionLabels);
            }

        } catch (IOException e) {
            logger.error("Failed to save tile: " + saveName, e);
        }
    }
    
    // [Final: Solid Polygon] Logic that fills holes and saves only the outline
    private void extractFilledPolygons(Geometry geom, int tileX, int tileY, String className, List<RegionLabel> regionLabels) {
        if (geom instanceof Polygon) {
            Polygon poly = (Polygon) geom;
            
            // Exterior Ring(Only the outline) is processed -> The interior is automatically filled
            List<List<Integer>> shellPoints = convertCoordinates(poly.getExteriorRing().getCoordinates(), tileX, tileY);
            if (shellPoints.size() > 2) {
                // Unify the type to “PolyGon”
                regionLabels.add(new RegionLabel(className, "PolyGon", shellPoints));
            }
            // Achieve the “Fill Hole” effect by removing the interior ring (hole) processing code
            
        } else if (geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                extractFilledPolygons(geom.getGeometryN(i), tileX, tileY, className, regionLabels);
            }
        }
    }
    
    private List<List<Integer>> convertCoordinates(Coordinate[] coords, int tileX, int tileY) {
        List<List<Integer>> points = new ArrayList<>();
        for (Coordinate c : coords) {
            int px = (int)((c.x - tileX) / downsample);
            int py = (int)((c.y - tileY) / downsample);
            
            px = Math.max(0, Math.min(patchSize, px));
            py = Math.max(0, Math.min(patchSize, py));
            
            // 중복 점 제거
            if (!points.isEmpty()) {
                List<Integer> last = points.get(points.size() - 1);
                if (last.get(0) == px && last.get(1) == py) continue;
            }
            
            points.add(Arrays.asList(px, py));
        }
        return points;
    }

    private boolean checkTissueInMask(int tileX, int tileY, int tileW, int tileH) {
        if (tissueMaskPixels == null) return true; 

        int mx = (int) (tileX * maskScaleFactor);
        int my = (int) (tileY * maskScaleFactor);
        int mw = (int) (tileW * maskScaleFactor);
        int mh = (int) (tileH * maskScaleFactor);

        if (mx < 0) mx = 0;
        if (my < 0) my = 0;
        if (mx + mw > maskWidth) mw = maskWidth - mx;
        if (my + mh > maskHeight) mh = maskHeight - my;
        
        if (mw <= 0 || mh <= 0) return false;

        int tissueCount = 0;
        int totalPixels = mw * mh;
        int threshold = 255 - (int)glassThreshold;

        for (int y = 0; y < mh; y++) {
            int rowOffset = (my + y) * maskWidth;
            for (int x = 0; x < mw; x++) {
                int rgb = tissueMaskPixels[rowOffset + mx + x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                double avg = (r + g + b) / 3.0;
                if (avg <= threshold) tissueCount++;
            }
        }
        return ((double)tissueCount / totalPixels) >= percentageThreshold;
    }

    private void printStatistics() {
        logger.info("=" * 60);
        logger.info("TILE GENERATION STATISTICS (Final: Solid Polygons)");
        logger.info("Total potential tiles: {}", totalTilesProcessed.get());
        logger.info("Tiles actually saved: {}", tilesSaved.get());
        logger.info("Annotations Generated: {}", groupedAnnotations.size());
        logger.info("=" * 60);
    }
    
    public void saveJson() throws IOException {
        if (groupedAnnotations.isEmpty()) return;
        
        String imageName = imageData.getServer().getMetadata().getName();
        int dotIndex = imageName.lastIndexOf('.');
        if (dotIndex > 0) imageName = imageName.substring(0, dotIndex);
        
        String jsonFileName = imageName + "_" + datasetName + "_output.json";
        File outputFile = dbPath.resolve(jsonFileName).toFile();
        
        Map<String, Object> finalJson = new HashMap<>();
        finalJson.put("label_type", modelType);
        finalJson.put("source", "labelset");
        finalJson.put("data", new ArrayList<>(groupedAnnotations.values()));

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
            this.className = className; this.type = type; this.points = points;
        }
    }
    
    class TileData {
        String fileName;
        String set;
        String classLabel;
        List<RegionLabel> regionLabel = new ArrayList<>();
        int retestset = 0; int rotation_angle = 0; int width; int height;
        public TileData(String fileName, String set, String classLabel, int width, int height) {
            this.fileName = fileName; this.set = set; this.classLabel = classLabel;
            this.width = width; this.height = height;
        }
    }
}