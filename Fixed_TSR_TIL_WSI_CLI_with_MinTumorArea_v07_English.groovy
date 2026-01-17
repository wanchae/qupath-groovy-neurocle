// QuPath WSI TSR & TIL Integrated Analysis CLI Script
// ULTRA-OPTIMIZED VERSION: Maximum performance for large WSI images
// CLI Version: Parameters configured at the top of the script
// Use "Run for Project" in QuPath to apply to multiple images
// MODIFIED: Added minimum tumor area criteria for 4-side detection
// FIXED: QuPath v0.6 compatibility - putMeasurement() replaced with put()
// ADDED: Metadata support for Annotation mode - metrics visible in UI
// v07 FIX: Cell object collection logic - explicit detection/point annotation handling

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.roi.EllipseROI
import qupath.lib.roi.PointsROI
import qupath.lib.roi.RoiTools
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI
import qupath.lib.regions.ImagePlane
import qupath.lib.images.ImageData
import qupath.lib.regions.RegionRequest
import qupath.lib.common.ColorTools
import qupath.lib.measurements.MeasurementList
import org.locationtech.jts.geom.util.AffineTransformation
import qupath.lib.roi.GeometryTools
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.geom.Envelope
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Paths
import static qupath.lib.gui.scripting.QPEx.*

// =====================================================
// PARAMETERS CONFIGURATION
// =====================================================
// Configure all parameters in this section

// --- TSR Basic Settings ---
double CIRCLE_DIAMETER_MM = 1.0                    // TSR circle diameter (mm)
double MIN_TUMOR_PERCENTAGE = 0                  // Minimum tumor percentage (%)
double MIN_TISSUE_PERCENTAGE = 70.0                // Minimum tissue percentage (%) - reduced from 70 to 50
double OVERLAP_RATIO = 0.9                         // Overlap ratio (0.0 ~ 1.0)
int MAX_ROIS_PER_IMAGE = 100000                    // Maximum ROIs per image

// --- NEW: Minimum Tumor Area Settings for 4-side Detection ---
double MIN_TUMOR_AREA_UM2 = 500.0                  // Minimum tumor area in quadrants (μm²) - 0 to disable
boolean USE_AND_CONDITION_FOR_AREA = false          // true: Use MIN_TUMOR_PERCENTAGE and MIN_TUMOR_AREA with AND condition
                                                    // false: Use only MIN_TUMOR_AREA if MIN_TUMOR_PERCENTAGE is 0

// --- Output Method Settings ---
String OUTPUT_METHOD = "Annotation"                // "Annotation" (Best TSR per tissue) or "Detection" (All circles for heatmap)
boolean CREATE_HEATMAP_DATA = true                 // Not used - QuPath Measurement Maps handle visualization
boolean KEEP_ALL_ANNOTATIONS = false               // Keep all circle annotations (false = keep only MAX values)


// --- Class Names ---
String TISSUE_CLASS_NAME = "Tissue"                // Tissue class name
String TUMOR_CLASS_NAME = "Tumor_HR"               // Tumor class name
String RESULT_CLASS_NAME = "TSR"                   // Result class name
List<String> EXCLUDE_CLASSES = ["BG", "Necrosis", "Normal"]  // Classes to exclude from stroma

// --- Cell Detection Settings ---
String TUMOR_CELL_CLASS = "epithelial-cell"        // Tumor cell class
String STROMA_CELL_CLASS = "connective-tissue-cell"  // Stroma cell class
String IMMUNE_CELL_CLASS = "lymphocyte,plasma-cell"  // Immune cell class (comma-separated)
String OTHER_CELL_CLASS = "neutrophil,eosinophil,mitosis"  // Other cell class

// --- NEW v07: Cell Object Type Selection ---
String CELL_OBJECT_TYPE = "Both"                   // "Detection", "PointAnnotation", or "Both"
                                                    // Detection: Use only PathDetectionObject (HoVer-NeXt, etc.)
                                                    // PointAnnotation: Use only Point annotation (manual marking)
                                                    // Both: Use both types (recommended)

// --- TIL Analysis Settings ---
boolean ENABLE_TIL_ANALYSIS = true                 // Enable TIL analysis
String TIL_ANALYSIS_MODE = "All"                   // "All", "eTILs", "etTILs", "esTILs", "eaTILs", "easTILs"
double TIL_DISTANCE_THRESHOLD_MICRONS = 30.0       // TIL distance threshold (microns)
double TIL_ADAPTIVE_THRESHOLD_PERCENTILE = 75      // Adaptive threshold percentile (0-100)
double AVERAGE_TIL_DIAMETER_MICRONS = 10.0          // Average TIL (lymphocyte) diameter (microns) - for Point annotation area calculation
double AVERAGE_TUMOR_CELL_DIAMETER_MICRONS = 15.0  // Average tumor cell diameter (microns)
double AVERAGE_STROMA_CELL_DIAMETER_MICRONS = 12.0 // Average stroma cell diameter (microns)


// --- Tissue/Tumor Detection Settings ---
String TISSUE_DETECTION_METHOD = "Annotation"      // "Annotation" (use existing) or "Manual" (auto-detect)
double MANUAL_EXPANSION_MICRONS = 100.0            // Expansion distance in Manual mode (microns)
double TISSUE_THRESHOLD = 250                      // Tissue detection threshold (0-255)
double DOWNSAMPLE = 16.0                           // Downsampling ratio

// --- Advanced Detection Algorithm Settings ---
boolean USE_ADVANCED_DETECTION = true             // Use Advanced Peripheral 4-side Detection
boolean USE_METHOD1 = true                         // Method 1: Peripheral elliptical quadrants (MANDATORY)
boolean USE_METHOD2 = false                         // Method 2: Radial peripheral sectors  
boolean USE_METHOD3 = false                         // Method 3: Ring-based detection
boolean USE_CENTRAL_CONCENTRATION_CHECK = false     // Central concentration check

// Advanced Algorithm Parameters
double PERIPHERAL_DISTANCE_RATIO = 0.65            // Peripheral Distance Ratio (0.0-1.0)
double ELLIPSE_RADIUS_RATIO = 0.35                 // Ellipse Radius Ratio (0.0-1.0)
double CENTRAL_RADIUS_RATIO = 0.40                 // Central Radius Ratio (0.0-1.0)
double CENTRAL_CONCENTRATION_LIMIT = 0.80          // Central Concentration Limit (0.0-1.0)
double RING_INNER_RATIO = 0.60                     // Ring Inner Ratio (0.0-1.0)
double RING_OUTER_RATIO = 0.90                     // Ring Outer Ratio (0.0-1.0)
double RADIAL_INNER_RATIO = 0.70                   // Radial Inner Ratio (0.0-1.0)
double RADIAL_OUTER_RATIO = 0.95                   // Radial Outer Ratio (0.0-1.0)
int REQUIRED_RING_SECTORS = 8                      // Required Ring Sectors (1-12)
int REQUIRED_RADIAL_DIRECTIONS = 4                 // Required Radial Directions (1-8)


// --- Output Settings ---
String EXPORT_FOLDER = "Results_TSR_TIL_WSI_007b"       // Result folder name (without timestamp)
boolean INCLUDE_TIMESTAMP = true                   // Include timestamp in filename (folder always without timestamp)
boolean CREATE_VISUAL_ANNOTATIONS = true           // Whether to create visual annotations
boolean SHOW_ALGORITHM_VALIDATION_ANNOTATIONS = false  // Show algorithm validation annotations (for debugging)
boolean DEBUG_MODE = false                         // Debug mode (detailed logging)

// --- Performance Settings ---
boolean USE_PARALLEL_PROCESSING = true             // Whether to use parallel processing
int NUM_THREADS = Math.min(Runtime.runtime.availableProcessors(), 16)  // Number of threads
boolean CALCULATE_STATISTICS = true                // Whether to calculate statistics
boolean SHOW_DETAILED_PROGRESS = true              // Whether to show detailed progress

// --- WSI Performance Settings ---
int BATCH_SIZE = 50                                // Batch size
int UPDATE_INTERVAL = 10                           // Update interval

// --- Ultra-Optimization Settings ---
boolean FAST_APPROXIMATION = true                  // Fast approximation (5% accuracy loss, 2x speed improvement)
int BATCH_PARALLEL_THREADS = 4                     // Batch parallel Number of threads
boolean ENABLE_EARLY_REJECTION = true              // Enable early rejection
double SAMPLING_RATIO = 1.0                        // Sampling ratio (0.1 ~ 1.0)
boolean USE_SPATIAL_INDEX = true                   // Use spatial index
boolean USE_TISSUE_AREA_CACHE = true               // Use tissue area cache

// =====================================================
// END OF PARAMETER CONFIGURATION
// =====================================================

println "==================================================="
println "TSR & TIL Integrated Analysis CLI - ULTRA-OPTIMIZED"
println "==================================================="
println "Starting analysis with the following parameters:"
println "  Circle Diameter: ${CIRCLE_DIAMETER_MM} mm"
println "  Min Tumor %: ${MIN_TUMOR_PERCENTAGE}%"
println "  Min Tissue %: ${MIN_TISSUE_PERCENTAGE}%"
println "  Output Method: ${OUTPUT_METHOD}"
if (OUTPUT_METHOD == "Annotation") {
    println "    - Keep only MAX values per tissue region"
} else if (OUTPUT_METHOD == "Detection") {
    println "    - Create all circles as detections"
    println "    - Use QuPath Measurement Maps for heatmap visualization"
}
println "  TIL Analysis: ${ENABLE_TIL_ANALYSIS ? 'Enabled' : 'Disabled'}"
if (ENABLE_TIL_ANALYSIS) {
    println "    - Cell Object Type: ${CELL_OBJECT_TYPE}"
    println "    - Mode: ${TIL_ANALYSIS_MODE}"
    println "    - Distance Threshold: ${TIL_DISTANCE_THRESHOLD_MICRONS} µm"
    println "    - TIL Diameter: ${AVERAGE_TIL_DIAMETER_MICRONS} µm"
}
if (MIN_TUMOR_AREA_UM2 > 0) {
    println "  ★ Min Tumor Area (4-SIDE CHECK): ${MIN_TUMOR_AREA_UM2} μm²"
    println "    → Automatically performs 4-side peripheral detection"
    println "    → ALL 4 quadrants must have >= ${MIN_TUMOR_AREA_UM2} μm² tumor"
    if (MIN_TUMOR_PERCENTAGE > 0 && USE_AND_CONDITION_FOR_AREA) {
        println "    → Combined with Min Tumor %: ${MIN_TUMOR_PERCENTAGE}% (AND condition)"
    } else if (MIN_TUMOR_PERCENTAGE == 0) {
        println "    → Using ONLY area criterion (Min Tumor % = 0)"
    }
}
println "  Advanced Detection: ${USE_ADVANCED_DETECTION ? 'Enabled' : 'Disabled'}"
if (USE_ADVANCED_DETECTION) {
    println "    - Additional validation methods beyond 4-side check"
    println "    - Methods: ${[USE_METHOD1 ? 'Method1' : null, USE_METHOD2 ? 'Method2' : null, USE_METHOD3 ? 'Method3' : null].findAll{it != null}.join(', ')}"
}
println "  Parallel Processing: ${USE_PARALLEL_PROCESSING ? 'Enabled' : 'Disabled'}"
if (USE_PARALLEL_PROCESSING) {
    println "    - Threads: ${NUM_THREADS}"
    println "    - Batch Size: ${BATCH_SIZE}"
}
println "  Performance Mode: ${FAST_APPROXIMATION ? 'Fast Approximation' : 'Standard'}"
println "==================================================="

// =====================================================
// MEMORY-OPTIMIZED TISSUE AREA CACHE
// =====================================================

class OptimizedTissueAreaCache {
    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>()
    private final int gridSize
    
    OptimizedTissueAreaCache(int gridSize) {
        this.gridSize = gridSize
    }
    
    String generateKey(ROI roi) {
        double x = roi.getCentroidX()
        double y = roi.getCentroidY()
        int gridX = (int)(x / gridSize)
        int gridY = (int)(y / gridSize)
        return "${gridX}_${gridY}"
    }
    
    Double get(String key) {
        return cache.get(key)
    }
    
    void put(String key, Double value) {
        cache.put(key, value)
    }
    
    void clear() {
        cache.clear()
    }
}

// =====================================================
// OPTIMIZED SPATIAL INDEX
// =====================================================

class OptimizedSpatialIndex {
    private final STRtree index = new STRtree()
    private final Map<Object, PathAnnotationObject> geometryToObject = [:]
    
    void insert(PathAnnotationObject obj) {
        def geom = obj.getROI().getGeometry()
        index.insert(geom.getEnvelopeInternal(), obj)
        geometryToObject[geom] = obj
    }
    
    void build() {
        index.build()
    }
    
    List<PathAnnotationObject> query(ROI roi) {
        def envelope = roi.getGeometry().getEnvelopeInternal()
        return index.query(envelope)
    }
    
    boolean hasAnyIntersection(ROI roi) {
        def geom = roi.getGeometry()
        def candidates = query(roi)
        return candidates.any { candidate ->
            candidate.getROI().getGeometry().intersects(geom)
        }
    }
}

// =====================================================
// INTEGRATED CONFIGURATION CLASS
// =====================================================

class IntegratedConfig {
    double circleDiameterMm
    double minTumorPercentage
    double minTissuePercentage
    double overlapRatio
    int maxROIsPerImage
    int tissueThreshold
    double downsample
    String tissueClassName
    String tumorClassName
    String resultClassName
    List<String> excludeClasses
    String exportFolder
    boolean includeTimestamp
    boolean createVisualAnnotations
    boolean showDetailedProgress
    boolean useParallelProcessing
    int numThreads
    boolean calculateStatistics
    boolean debugMode
    boolean showAlgorithmValidationAnnotations
    
    // Output Method Settings
    String outputMethod
    boolean createHeatmapData
    boolean keepAllAnnotations
    
    // Advanced Detection Algorithm Settings
    boolean useAdvancedDetection
    boolean useMethod1
    boolean useMethod2
    boolean useMethod3
    boolean useCentralConcentrationCheck
    double peripheralDistanceRatio
    double ellipseRadiusRatio
    double centralRadiusRatio
    double centralConcentrationLimit
    double ringInnerRatio
    double ringOuterRatio
    double radialInnerRatio
    double radialOuterRatio
    int requiredRingSectors
    int requiredRadialDirections
    
    // NEW: Minimum Tumor Area Settings
    double minTumorAreaUm2
    boolean useAndConditionForArea
    
    // TIL Configuration
    boolean enableTILAnalysis
    String tilAnalysisMode
    double tilDistanceThresholdMicrons
    double tilAdaptiveThresholdPercentile
    double averageTILDiameterMicrons
    double averageTumorCellDiameterMicrons
    double averageStromaCellDiameterMicrons
    String tumorCellClass
    String stromaCellClass
    String immuneCellClass
    String otherCellClass
    String cellObjectType          // NEW v07
    String tissueDetectionMethod
    double manualExpansionMicrons
    
    // Performance Settings
    int batchSize
    int updateInterval
    boolean fastApproximation
    int batchParallelThreads
    boolean enableEarlyRejection
    double samplingRatio
    boolean useSpatialIndex
    boolean useTissueAreaCache
}

// Create configuration
def config = new IntegratedConfig()
config.circleDiameterMm = CIRCLE_DIAMETER_MM
config.minTumorPercentage = MIN_TUMOR_PERCENTAGE
config.minTissuePercentage = MIN_TISSUE_PERCENTAGE
config.overlapRatio = OVERLAP_RATIO
config.maxROIsPerImage = MAX_ROIS_PER_IMAGE
config.tissueThreshold = TISSUE_THRESHOLD
config.downsample = DOWNSAMPLE
config.tissueClassName = TISSUE_CLASS_NAME
config.tumorClassName = TUMOR_CLASS_NAME
config.resultClassName = RESULT_CLASS_NAME
config.excludeClasses = EXCLUDE_CLASSES
config.exportFolder = EXPORT_FOLDER
config.includeTimestamp = INCLUDE_TIMESTAMP
config.createVisualAnnotations = CREATE_VISUAL_ANNOTATIONS
config.showDetailedProgress = SHOW_DETAILED_PROGRESS
config.useParallelProcessing = USE_PARALLEL_PROCESSING
config.numThreads = NUM_THREADS
config.calculateStatistics = CALCULATE_STATISTICS
config.debugMode = DEBUG_MODE
config.showAlgorithmValidationAnnotations = SHOW_ALGORITHM_VALIDATION_ANNOTATIONS

// Output Method Settings
config.outputMethod = OUTPUT_METHOD
config.createHeatmapData = CREATE_HEATMAP_DATA
config.keepAllAnnotations = KEEP_ALL_ANNOTATIONS

// Advanced Detection Algorithm Settings
config.useAdvancedDetection = USE_ADVANCED_DETECTION
config.useMethod1 = USE_METHOD1
config.useMethod2 = USE_METHOD2
config.useMethod3 = USE_METHOD3
config.useCentralConcentrationCheck = USE_CENTRAL_CONCENTRATION_CHECK
config.peripheralDistanceRatio = PERIPHERAL_DISTANCE_RATIO
config.ellipseRadiusRatio = ELLIPSE_RADIUS_RATIO
config.centralRadiusRatio = CENTRAL_RADIUS_RATIO
config.centralConcentrationLimit = CENTRAL_CONCENTRATION_LIMIT
config.ringInnerRatio = RING_INNER_RATIO
config.ringOuterRatio = RING_OUTER_RATIO
config.radialInnerRatio = RADIAL_INNER_RATIO
config.radialOuterRatio = RADIAL_OUTER_RATIO
config.requiredRingSectors = REQUIRED_RING_SECTORS
config.requiredRadialDirections = REQUIRED_RADIAL_DIRECTIONS

// NEW: Minimum Tumor Area Settings
config.minTumorAreaUm2 = MIN_TUMOR_AREA_UM2
config.useAndConditionForArea = USE_AND_CONDITION_FOR_AREA

config.enableTILAnalysis = ENABLE_TIL_ANALYSIS
config.tilAnalysisMode = TIL_ANALYSIS_MODE
config.tilDistanceThresholdMicrons = TIL_DISTANCE_THRESHOLD_MICRONS
config.tilAdaptiveThresholdPercentile = TIL_ADAPTIVE_THRESHOLD_PERCENTILE
config.averageTILDiameterMicrons = AVERAGE_TIL_DIAMETER_MICRONS
config.averageTumorCellDiameterMicrons = AVERAGE_TUMOR_CELL_DIAMETER_MICRONS
config.averageStromaCellDiameterMicrons = AVERAGE_STROMA_CELL_DIAMETER_MICRONS
config.tumorCellClass = TUMOR_CELL_CLASS
config.stromaCellClass = STROMA_CELL_CLASS
config.immuneCellClass = IMMUNE_CELL_CLASS
config.otherCellClass = OTHER_CELL_CLASS
config.cellObjectType = CELL_OBJECT_TYPE  // NEW v07
config.tissueDetectionMethod = TISSUE_DETECTION_METHOD
config.manualExpansionMicrons = MANUAL_EXPANSION_MICRONS

config.batchSize = BATCH_SIZE
config.updateInterval = UPDATE_INTERVAL
config.fastApproximation = FAST_APPROXIMATION
config.batchParallelThreads = BATCH_PARALLEL_THREADS
config.enableEarlyRejection = ENABLE_EARLY_REJECTION
config.samplingRatio = SAMPLING_RATIO
config.useSpatialIndex = USE_SPATIAL_INDEX
config.useTissueAreaCache = USE_TISSUE_AREA_CACHE

// =====================================================
// TSR COLOR MAPPING FOR HEATMAP
// =====================================================
// Note: Not needed - QuPath Measurement Maps handle visualization automatically
/*
int getTSRColor(double tsrValue) {
    // TSR color mapping: Blue (low stroma/good) -> Red (high stroma/poor)
    tsrValue = Math.max(0.0, Math.min(1.0, tsrValue))
    
    int r, g, b
    if (tsrValue < 0.5) {
        // Blue to Green (0.0 to 0.5)
        double ratio = tsrValue * 2.0
        r = (int)(0 + ratio * 0)
        g = (int)(0 + ratio * 255)
        b = (int)(255 * (1 - ratio))
    } else {
        // Green to Red (0.5 to 1.0)
        double ratio = (tsrValue - 0.5) * 2.0
        r = (int)(0 + ratio * 255)
        g = (int)(255 * (1 - ratio))
        b = 0
    }
    
    // Pack RGB into single integer
    return (r << 16) | (g << 8) | b
}
*/

// =====================================================
// ADVANCED DETECTION VALIDATION FUNCTION (MODIFIED)
// =====================================================

boolean validateWithAdvancedDetection(
    def testROI,
    double tumorArea,
    def tumorAnnotations,
    IntegratedConfig config,
    def imageData  // Add imageData parameter for pixel size calculation
) {
    if (!config.useAdvancedDetection) {
        return true  // Skip validation if not using advanced detection
    }
    
    def centerX = testROI.getCentroidX()
    def centerY = testROI.getCentroidY()
    def radius = testROI.getBoundsWidth() / 2.0
    
    // Get pixel size for area conversion
    def pixelWidth = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
    def pixelHeight = imageData.getServer().getPixelCalibration().getPixelHeightMicrons()
    def pixelAreaUm2 = pixelWidth * pixelHeight
    
    def validationResults = []
    
    // Method 1: Peripheral elliptical quadrants (with minimum area check)
    if (config.useMethod1) {
        def peripheralDistance = radius * config.peripheralDistanceRatio
        def ellipseRadius = radius * config.ellipseRadiusRatio
        def quadrantCount = 0
        def quadrantAreaCount = 0  // Count quadrants meeting area criteria
        
        // Check 4 quadrants
        def quadrantCenters = [
            [centerX + peripheralDistance, centerY],  // Right
            [centerX - peripheralDistance, centerY],  // Left
            [centerX, centerY + peripheralDistance],  // Bottom
            [centerX, centerY - peripheralDistance]   // Top
        ]
        
        quadrantCenters.each { qCenter ->
            def ellipseROI = ROIs.createEllipseROI(
                qCenter[0] - ellipseRadius,
                qCenter[1] - ellipseRadius,
                ellipseRadius * 2,
                ellipseRadius * 2,
                ImagePlane.getDefaultPlane()
            )
            
            // Calculate tumor area in this quadrant
            def quadrantTumorArea = 0
            def hasTumor = false
            
            tumorAnnotations.each { tumor ->
                def tumorGeometry = tumor.getROI().getGeometry()
                def ellipseGeometry = ellipseROI.getGeometry()
                
                if (tumorGeometry.intersects(ellipseGeometry)) {
                    hasTumor = true
                    try {
                        def intersection = tumorGeometry.intersection(ellipseGeometry)
                        quadrantTumorArea += intersection.getArea()
                    } catch (Exception e) {
                        // Handle topology exceptions
                        hasTumor = true
                    }
                }
            }
            
            // Check if tumor exists (basic presence check)
            if (hasTumor) quadrantCount++
            
            // Convert area to μm² and check against minimum area threshold
            def quadrantTumorAreaUm2 = quadrantTumorArea * pixelAreaUm2
            
            // Apply area criteria based on configuration
            boolean meetsAreaCriteria = false
            if (config.minTumorPercentage > 0 && config.minTumorAreaUm2 > 0) {
                // Both criteria must be met (AND condition)
                if (config.useAndConditionForArea) {
                    // Calculate percentage for this quadrant
                    def ellipseArea = ellipseROI.getArea()
                    def quadrantTumorPercentage = (quadrantTumorArea / ellipseArea) * 100
                    meetsAreaCriteria = (quadrantTumorAreaUm2 >= config.minTumorAreaUm2) && 
                                       (quadrantTumorPercentage >= config.minTumorPercentage)
                } else {
                    // Use percentage if available, otherwise use absolute area
                    def ellipseArea = ellipseROI.getArea()
                    def quadrantTumorPercentage = (quadrantTumorArea / ellipseArea) * 100
                    meetsAreaCriteria = (quadrantTumorPercentage >= config.minTumorPercentage)
                }
            } else if (config.minTumorPercentage == 0 && config.minTumorAreaUm2 > 0) {
                // Only absolute area criterion
                meetsAreaCriteria = (quadrantTumorAreaUm2 >= config.minTumorAreaUm2)
            } else if (config.minTumorPercentage > 0 && config.minTumorAreaUm2 == 0) {
                // Only percentage criterion (original behavior)
                def ellipseArea = ellipseROI.getArea()
                def quadrantTumorPercentage = (quadrantTumorArea / ellipseArea) * 100
                meetsAreaCriteria = (quadrantTumorPercentage >= config.minTumorPercentage)
            } else {
                // No additional criteria beyond presence
                meetsAreaCriteria = hasTumor
            }
            
            if (meetsAreaCriteria) quadrantAreaCount++
            
            if (config.debugMode) {
                println "Quadrant at (${qCenter[0].round(2)}, ${qCenter[1].round(2)}): " +
                        "hasTumor=${hasTumor}, area=${quadrantTumorAreaUm2.round(2)} μm², " +
                        "meetsAreaCriteria=${meetsAreaCriteria}"
            }
        }
        
        // Use area-based count if area criteria are specified
        if (config.minTumorAreaUm2 > 0) {
            validationResults.add(quadrantAreaCount >= 4)
        } else {
            validationResults.add(quadrantCount >= 4)
        }
    }
    
    // Method 2: Radial peripheral sectors
    if (config.useMethod2) {
        def innerRadius = radius * config.radialInnerRatio
        def outerRadius = radius * config.radialOuterRatio
        def sectorCount = 0
        
        for (int i = 0; i < 4; i++) {
            def angle = i * Math.PI / 2
            def testX = centerX + Math.cos(angle) * (innerRadius + outerRadius) / 2
            def testY = centerY + Math.sin(angle) * (innerRadius + outerRadius) / 2
            
            def hasTumor = tumorAnnotations.any { tumor ->
                tumor.getROI().contains(testX, testY)
            }
            
            if (hasTumor) sectorCount++
        }
        
        validationResults.add(sectorCount >= config.requiredRadialDirections)
    }
    
    // Method 3: Ring-based detection
    if (config.useMethod3) {
        def innerRadius = radius * config.ringInnerRatio
        def outerRadius = radius * config.ringOuterRatio
        def ringSectorCount = 0
        
        for (int i = 0; i < 8; i++) {
            def angle = i * Math.PI / 4
            def testX = centerX + Math.cos(angle) * (innerRadius + outerRadius) / 2
            def testY = centerY + Math.sin(angle) * (innerRadius + outerRadius) / 2
            
            def hasTumor = tumorAnnotations.any { tumor ->
                tumor.getROI().contains(testX, testY)
            }
            
            if (hasTumor) ringSectorCount++
        }
        
        validationResults.add(ringSectorCount >= config.requiredRingSectors)
    }
    
    // Central concentration check (optional)
    if (config.useCentralConcentrationCheck) {
        def centralRadius = radius * config.centralRadiusRatio
        def centralROI = ROIs.createEllipseROI(
            centerX - centralRadius,
            centerY - centralRadius,
            centralRadius * 2,
            centralRadius * 2,
            ImagePlane.getDefaultPlane()
        )
        
        def centralTumorArea = 0
        tumorAnnotations.each { tumor ->
            def tumorGeometry = tumor.getROI().getGeometry()
            def centralGeometry = centralROI.getGeometry()
            if (tumorGeometry.intersects(centralGeometry)) {
                def intersection = tumorGeometry.intersection(centralGeometry)
                centralTumorArea += intersection.getArea()
            }
        }
        
        def centralConcentration = tumorArea > 0 ? (centralTumorArea / tumorArea) : 0
        validationResults.add(centralConcentration <= config.centralConcentrationLimit)
    }
    
    // All enabled methods must pass
    return validationResults.isEmpty() || validationResults.every { it == true }
}

// =====================================================
// COLLECT CELLS FUNCTION - NEW v07
// =====================================================

List collectCells(ROI testROI, def hierarchy, IntegratedConfig config) {
    def cells = []
    def roiGeometry = testROI.getGeometry()
    
    // Collect Detection objects if requested
    if (config.cellObjectType == "Detection" || config.cellObjectType == "Both") {
        def allDetections = hierarchy.getDetectionObjects()
        
        allDetections.each { detection ->
            def detectionROI = detection.getROI()
            if (detectionROI != null) {
                // Check if detection centroid is within test ROI
                if (testROI.contains(detectionROI.getCentroidX(), detectionROI.getCentroidY())) {
                    cells.add(detection)
                } else {
                    // For non-point detections, check geometry intersection
                    try {
                        def detectionGeometry = detectionROI.getGeometry()
                        if (roiGeometry.intersects(detectionGeometry)) {
                            cells.add(detection)
                        }
                    } catch (Exception e) {
                        // Skip invalid geometries
                    }
                }
            }
        }
    }
    
    // Collect Point annotations if requested
    if (config.cellObjectType == "PointAnnotation" || config.cellObjectType == "Both") {
        def pointAnnotations = hierarchy.getAnnotationObjects().findAll {
            it.getROI() instanceof qupath.lib.roi.PointsROI
        }
        
        pointAnnotations.each { annotation ->
            def pointROI = annotation.getROI()
            // For PointsROI, check if centroid is within test ROI
            if (testROI.contains(pointROI.getCentroidX(), pointROI.getCentroidY())) {
                cells.add(annotation)
            }
        }
    }
    
    return cells
}

// =====================================================
// ANALYZE TIL FUNCTION
// =====================================================

Map<String, Object> analyzeTILs(
    List cells,
    PathAnnotationObject tumorAnnotation,
    PathAnnotationObject tissueBounds,
    double distanceThreshold,
    double adaptiveThreshold,
    IntegratedConfig config,
    def hierarchy,
    def imageData,
    double stromaAreaPixels
) {
    def results = [:]
    
    if (cells.isEmpty()) {
        results['TILs_count'] = 0
        results['Tumor_count'] = 0
        results['Stroma_count'] = 0
        results['Other_count'] = 0
        results['Total_count'] = 0
        results['eTILs_percent'] = 0.0
        results['etTILs_percent'] = 0.0
        results['esTILs_percent'] = 0.0
        results['eaTILs_per_mm2'] = 0.0
        results['easTILs_percent'] = 0.0
        return results
    }
    
    // Categorize cells by class
    def tilCells = []
    def tumorCells = []
    def stromaCells = []
    def otherCells = []
    
    // Parse immune cell classes
    def immuneClasses = config.immuneCellClass.split(',').collect { it.trim().toLowerCase() }
    def otherClasses = config.otherCellClass.split(',').collect { it.trim().toLowerCase() }
    
    cells.each { cell ->
        def cellClass = cell.getPathClass()?.toString()?.toLowerCase()
        if (!cellClass) return
        
        if (immuneClasses.any { cellClass.contains(it) }) {
            tilCells.add(cell)
        } else if (cellClass.contains(config.tumorCellClass.toLowerCase())) {
            tumorCells.add(cell)
        } else if (cellClass.contains(config.stromaCellClass.toLowerCase())) {
            stromaCells.add(cell)
        } else if (otherClasses.any { cellClass.contains(it) }) {
            otherCells.add(cell)
        }
    }
    
    // Calculate pixel to micron conversion
    def pixelWidth = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
    def pixelHeight = imageData.getServer().getPixelCalibration().getPixelHeightMicrons()
    def pixelToMicron = Math.sqrt(pixelWidth * pixelHeight)
    
    // Get tumor boundary for distance calculations
    def tumorBoundary = tumorAnnotation?.getROI()?.getGeometry()?.getBoundary()
    
    // Calculate eTILs, etTILs, esTILs
    def eTILs = []
    def etTILs = []
    def esTILs = []
    
    tilCells.each { til ->
        def tilX = til.getROI().getCentroidX()
        def tilY = til.getROI().getCentroidY()
        def tilPoint = GeometryTools.getDefaultFactory().createPoint(
            new org.locationtech.jts.geom.Coordinate(tilX, tilY)
        )
        
        if (tumorBoundary && !tumorBoundary.isEmpty()) {
            def distance = tilPoint.distance(tumorBoundary) * pixelToMicron
            
            if (distance <= distanceThreshold) {
                eTILs.add(til)
                
                // Check if inside tumor (etTILs) or in stroma (esTILs)
                if (tumorAnnotation.getROI().contains(tilX, tilY)) {
                    etTILs.add(til)
                } else {
                    esTILs.add(til)
                }
            }
        }
    }
    
    // Calculate area-based TIL metrics
    def tilCellRadius = config.averageTILDiameterMicrons / 2.0
    def tilCellAreaUm2 = Math.PI * tilCellRadius * tilCellRadius
    def totalTilAreaUm2 = tilCells.size() * tilCellAreaUm2
    
    def tumorCellRadius = config.averageTumorCellDiameterMicrons / 2.0
    def tumorCellAreaUm2 = Math.PI * tumorCellRadius * tumorCellRadius
    
    def stromaCellRadius = config.averageStromaCellDiameterMicrons / 2.0
    def stromaCellAreaUm2 = Math.PI * stromaCellRadius * stromaCellRadius
    
    // Calculate stroma area in mm²
    def stromaAreaMm2 = stromaAreaPixels * pixelWidth * pixelHeight / 1_000_000.0
    
    // Calculate percentages
    def totalCells = tilCells.size() + tumorCells.size() + stromaCells.size() + otherCells.size()
    def eTILsPercent = totalCells > 0 ? (eTILs.size() * 100.0 / totalCells) : 0
    def etTILsPercent = tumorCells.size() > 0 ? (etTILs.size() * 100.0 / tumorCells.size()) : 0
    def esTILsPercent = stromaCells.size() > 0 ? (esTILs.size() * 100.0 / stromaCells.size()) : 0
    
    // Calculate eaTILs (area-based TILs per mm²)
    def eaTILsPerMm2 = stromaAreaMm2 > 0 ? (tilCells.size() / stromaAreaMm2) : 0
    
    // Calculate easTILs (area-based percentage)
    def totalStromaCellArea = stromaCells.size() * stromaCellAreaUm2
    def easTILsPercent = totalStromaCellArea > 0 ? 
        (totalTilAreaUm2 * 100.0 / totalStromaCellArea) : 0
    
    // Store results based on analysis mode
    results['TILs_count'] = tilCells.size()
    results['Tumor_count'] = tumorCells.size()
    results['Stroma_count'] = stromaCells.size()
    results['Other_count'] = otherCells.size()
    results['Total_count'] = totalCells
    results['eTILs_percent'] = eTILsPercent
    results['etTILs_percent'] = etTILsPercent
    results['esTILs_percent'] = esTILsPercent
    results['eaTILs_per_mm2'] = eaTILsPerMm2
    results['easTILs_percent'] = easTILsPercent
    results['TIL_area_mm2'] = totalTilAreaUm2 / 1_000_000.0
    results['Stroma_area_mm2'] = stromaAreaMm2
    
    return results
}

// =====================================================
// MEDIAN CALCULATION
// =====================================================

double calculateMedian(List<Double> values) {
    if (values.isEmpty()) return 0
    def sorted = values.sort()
    int n = sorted.size()
    if (n % 2 == 0) {
        return (sorted[n/2 - 1] + sorted[n/2]) / 2.0
    } else {
        return sorted[n/2]
    }
}

// =====================================================
// MAIN RUN INTEGRATED ANALYSIS FUNCTION
// =====================================================

def runIntegratedAnalysis(IntegratedConfig config) {
    def imageData = getCurrentImageData()
    if (!imageData) {
        println "ERROR: No image loaded"
        return
    }
    
    def server = imageData.getServer()
    def hierarchy = imageData.getHierarchy()
    def pixelWidth = server.getPixelCalibration().getPixelWidthMicrons()
    def pixelHeight = server.getPixelCalibration().getPixelHeightMicrons()
    
    def log = { msg ->
        if (config.showDetailedProgress || config.debugMode) {
            println msg
        }
    }
    
    log "Image: ${server.getMetadata().getName()}"
    log "Pixel size: ${pixelWidth} x ${pixelHeight} µm"
    
    // Get tissue annotations
    def tissueAnnotations = hierarchy.getAnnotationObjects().findAll {
        if (it.getROI() instanceof PointsROI) return false
        if (it.getROI().getArea() < 10000) return false
        def className = it.getPathClass()?.toString()
        return className?.equalsIgnoreCase(config.tissueClassName)
    }
    
    if (tissueAnnotations.isEmpty()) {
        log "ERROR: No tissue annotations with class '${config.tissueClassName}' found"
        log "Available classes: ${hierarchy.getAnnotationObjects().collect{it.getPathClass()?.toString()}.unique()}"
        return
    }
    
    log "Found ${tissueAnnotations.size()} tissue annotation(s)"
    
    // Get tumor annotations
    def tumorAnnotations = hierarchy.getAnnotationObjects().findAll {
        if (it.getROI() instanceof PointsROI) return false
        def className = it.getPathClass()?.toString()
        return className?.equalsIgnoreCase(config.tumorClassName)
    }
    
    if (tumorAnnotations.isEmpty()) {
        log "WARNING: No tumor annotations with class '${config.tumorClassName}' found"
        log "Will proceed with TSR = 1.0 for all ROIs"
    } else {
        log "Found ${tumorAnnotations.size()} tumor annotation(s)"
    }
    
    // Check for cell detections/annotations if TIL analysis is enabled
    if (config.enableTILAnalysis) {
        def allDetections = hierarchy.getDetectionObjects()
        def pointAnnotations = hierarchy.getAnnotationObjects().findAll {
            it.getROI() instanceof PointsROI
        }
        
        println "==================================================="
        println "TIL Analysis Configuration:"
        println "  - Cell Object Type: ${config.cellObjectType}"
        println "  - Detection objects found: ${allDetections.size()}"
        println "  - Point annotation objects found: ${pointAnnotations.size()}"
        
        if (config.cellObjectType == "Detection" && allDetections.isEmpty()) {
            println "  ★ WARNING: No detection objects found!"
            println "    Cell Object Type is set to 'Detection' but no detections exist."
            println "    Consider setting CELL_OBJECT_TYPE to 'PointAnnotation' or 'Both'"
        } else if (config.cellObjectType == "PointAnnotation" && pointAnnotations.isEmpty()) {
            println "  ★ WARNING: No point annotation objects found!"
            println "    Cell Object Type is set to 'PointAnnotation' but no point annotations exist."
            println "    Consider setting CELL_OBJECT_TYPE to 'Detection' or 'Both'"
        } else if (config.cellObjectType == "Both" && allDetections.isEmpty() && pointAnnotations.isEmpty()) {
            println "  ★ WARNING: No cell objects found (neither detections nor point annotations)!"
            println "    TIL analysis will return 0 for all metrics."
        }
        
        // Display cell class information
        if (!allDetections.isEmpty()) {
            def detectionClasses = allDetections.collect { it.getPathClass()?.toString() }.findAll { it != null }.unique()
            println "  - Detection classes found: ${detectionClasses.join(', ')}"
        }
        if (!pointAnnotations.isEmpty()) {
            def pointClasses = pointAnnotations.collect { it.getPathClass()?.toString() }.findAll { it != null }.unique()
            println "  - Point annotation classes found: ${pointClasses.join(', ')}"
        }
        
        println "  - Immune cell classes to detect: ${config.immuneCellClass}"
        println "  - Tumor cell class to detect: ${config.tumorCellClass}"
        println "  - Stroma cell class to detect: ${config.stromaCellClass}"
        println "==================================================="
    }
    
    // Calculate circle parameters
    def circleDiameterPixels = config.circleDiameterMm * 1000 / pixelWidth
    def circleRadius = circleDiameterPixels / 2
    def stepSize = circleDiameterPixels * (1 - config.overlapRatio)
    
    log "Circle diameter: ${circleDiameterPixels.round(2)} pixels (${config.circleDiameterMm} mm)"
    log "Step size: ${stepSize.round(2)} pixels (${config.overlapRatio * 100}% overlap)"
    
    // Prepare optimizations
    OptimizedSpatialIndex spatialIndex = null
    OptimizedTissueAreaCache tissueCache = null
    
    if (config.useSpatialIndex && !tumorAnnotations.isEmpty()) {
        spatialIndex = new OptimizedSpatialIndex()
        tumorAnnotations.each { spatialIndex.insert(it) }
        spatialIndex.build()
        log "Spatial index built for ${tumorAnnotations.size()} tumor annotations"
    }
    
    if (config.useTissueAreaCache) {
        tissueCache = new OptimizedTissueAreaCache((int)stepSize)
        log "Tissue area cache initialized with grid size: ${(int)stepSize}"
    }
    
    // Generate positions
    def positions = []
    tissueAnnotations.each { tissue ->
        def bounds = tissue.getROI().getBoundsX()
        def minX = bounds - circleRadius
        def maxX = bounds + tissue.getROI().getBoundsWidth() + circleRadius
        def minY = tissue.getROI().getBoundsY() - circleRadius
        def maxY = minY + tissue.getROI().getBoundsHeight() + circleRadius
        
        for (double y = minY; y <= maxY; y += stepSize) {
            for (double x = minX; x <= maxX; x += stepSize) {
                if (config.samplingRatio < 1.0 && Math.random() > config.samplingRatio) {
                    continue
                }
                positions.add([x, y])
            }
        }
    }
    
    log "Generated ${positions.size()} positions to test"
    
    // Process positions
    def validResults = Collections.synchronizedList([])
    def validationAnnotations = Collections.synchronizedList([])
    def processedCount = new AtomicInteger(0)
    def rejectedCount = new AtomicInteger(0)
    def startTime = System.currentTimeMillis()
    
    def processBatch = { batch ->
        batch.each { pos ->
            if (validResults.size() >= config.maxROIsPerImage) {
                return
            }
            
            double x = pos[0]
            double y = pos[1]
            
            def testROI = ROIs.createEllipseROI(
                x - circleRadius,
                y - circleRadius,
                circleDiameterPixels,
                circleDiameterPixels,
                ImagePlane.getDefaultPlane()
            )
            
            def testGeometry = testROI.getGeometry()
            
            // Early rejection optimization
            if (config.enableEarlyRejection) {
                def hasAnyTissue = tissueAnnotations.any { tissue ->
                    tissue.getROI().getGeometry().intersects(testGeometry)
                }
                
                if (!hasAnyTissue) {
                    rejectedCount.incrementAndGet()
                    return
                }
                
                if (spatialIndex != null && !spatialIndex.hasAnyIntersection(testROI)) {
                    rejectedCount.incrementAndGet()
                    return
                }
            }
            
            // Calculate tissue area
            double tissueArea = 0
            String cacheKey = tissueCache?.generateKey(testROI)
            
            if (tissueCache != null && cacheKey != null) {
                Double cachedArea = tissueCache.get(cacheKey)
                if (cachedArea != null) {
                    tissueArea = cachedArea
                }
            }
            
            if (tissueArea == 0) {
                tissueAnnotations.each { tissue ->
                    def tissueGeometry = tissue.getROI().getGeometry()
                    if (tissueGeometry.intersects(testGeometry)) {
                        def intersection = tissueGeometry.intersection(testGeometry)
                        tissueArea += intersection.getArea()
                    }
                }
                
                if (tissueCache != null && cacheKey != null) {
                    tissueCache.put(cacheKey, tissueArea)
                }
            }
            
            double totalArea = testROI.getArea()
            double tissuePercentage = (tissueArea / totalArea) * 100
            
            if (tissuePercentage < config.minTissuePercentage) {
                rejectedCount.incrementAndGet()
                return
            }
            
            // Calculate tumor area
            double tumorArea = 0
            PathAnnotationObject intersectingTumor = null
            
            if (spatialIndex != null) {
                def candidates = spatialIndex.query(testROI)
                candidates.each { tumor ->
                    def tumorGeometry = tumor.getROI().getGeometry()
                    if (tumorGeometry.intersects(testGeometry)) {
                        def intersection = tumorGeometry.intersection(testGeometry)
                        double area = intersection.getArea()
                        if (area > tumorArea) {
                            tumorArea = area
                            intersectingTumor = tumor
                        }
                    }
                }
            } else {
                tumorAnnotations.each { tumor ->
                    def tumorGeometry = tumor.getROI().getGeometry()
                    if (tumorGeometry.intersects(testGeometry)) {
                        def intersection = tumorGeometry.intersection(testGeometry)
                        double area = intersection.getArea()
                        if (area > tumorArea) {
                            tumorArea = area
                            intersectingTumor = tumor
                        }
                    }
                }
            }
            
            double tumorPercentage = (tumorArea / tissueArea) * 100
            
            // Check tumor percentage criteria (if specified)
            if (config.minTumorPercentage > 0 && tumorPercentage < config.minTumorPercentage) {
                rejectedCount.incrementAndGet()
                return
            }
            
            // Check minimum tumor area with 4-side peripheral detection
            // When MIN_TUMOR_AREA_UM2 > 0, ALWAYS perform 4-side check
            // This ensures tumor is distributed around the ROI, not concentrated in one area
            if (config.minTumorAreaUm2 > 0) {
                def pixelAreaUm2 = pixelWidth * pixelHeight
                
                // Perform 4-side peripheral detection
                def radius = testROI.getBoundsWidth() / 2.0
                def peripheralDistance = radius * 0.65  // Use default peripheral distance ratio
                def ellipseRadius = radius * 0.35       // Use default ellipse radius ratio
                
                def quadrantCenters = [
                    [x + peripheralDistance, y],  // Right
                    [x - peripheralDistance, y],  // Left
                    [x, y + peripheralDistance],  // Bottom
                    [x, y - peripheralDistance]   // Top
                ]
                
                int quadrantsPassingAreaCheck = 0
                
                quadrantCenters.eachWithIndex { qCenter, idx ->
                    def ellipseROI = ROIs.createEllipseROI(
                        qCenter[0] - ellipseRadius,
                        qCenter[1] - ellipseRadius,
                        ellipseRadius * 2,
                        ellipseRadius * 2,
                        ImagePlane.getDefaultPlane()
                    )
                    
                    // Calculate tumor area in this quadrant
                    def quadrantTumorArea = 0
                    
                    tumorAnnotations.each { tumor ->
                        def tumorGeometry = tumor.getROI().getGeometry()
                        def ellipseGeometry = ellipseROI.getGeometry()
                        
                        if (tumorGeometry.intersects(ellipseGeometry)) {
                            try {
                                def intersection = tumorGeometry.intersection(ellipseGeometry)
                                quadrantTumorArea += intersection.getArea()
                            } catch (Exception e) {
                                // Handle topology exceptions - assume some tumor presence
                                quadrantTumorArea += ellipseROI.getArea() * 0.01
                            }
                        }
                    }
                    
                    // Convert to μm² and check against threshold
                    def quadrantTumorAreaUm2 = quadrantTumorArea * pixelAreaUm2
                    
                    if (quadrantTumorAreaUm2 >= config.minTumorAreaUm2) {
                        quadrantsPassingAreaCheck++
                    }
                    
                    if (config.debugMode) {
                        def direction = ['Right', 'Left', 'Bottom', 'Top'][idx]
                        println "  Quadrant ${direction}: tumor area = ${quadrantTumorAreaUm2.round(2)} μm² " +
                                "(threshold: ${config.minTumorAreaUm2} μm²) - " +
                                (quadrantTumorAreaUm2 >= config.minTumorAreaUm2 ? "PASS" : "FAIL")
                    }
                }
                
                // ALL 4 quadrants must meet the minimum tumor area requirement
                if (quadrantsPassingAreaCheck < 4) {
                    rejectedCount.incrementAndGet()
                    if (config.debugMode) {
                        println "  ROI REJECTED: Only ${quadrantsPassingAreaCheck}/4 quadrants have sufficient tumor area"
                    }
                    return
                }
                
                if (config.debugMode) {
                    println "  ROI PASSED: All 4 quadrants have sufficient tumor area (>= ${config.minTumorAreaUm2} μm²)"
                }
            }
            
            // Advanced Detection Algorithm Validation (optional additional checks)
            if (config.useAdvancedDetection) {
                def isValid = validateWithAdvancedDetection(testROI, tumorArea, tumorAnnotations, config, imageData)
                if (!isValid) {
                    rejectedCount.incrementAndGet()
                    if (config.showAlgorithmValidationAnnotations || config.debugMode) {
                        def annotation = PathObjects.createAnnotationObject(testROI)
                        annotation.setPathClass(PathClass.fromString("FAIL_ADVANCED"))
                        validationAnnotations.add(annotation)
                        
                        if (config.debugMode) {
                            log "Advanced Detection rejected ROI at (${x.round(2)}, ${y.round(2)})"
                        }
                    }
                    return
                }
            }
            
            // Calculate stroma area
            double stromaArea = tissueArea - tumorArea
            double tsr = stromaArea / tissueArea
            
            // TIL analysis if enabled
            Map<String, Object> tilMetrics = null
            if (config.enableTILAnalysis) {
                // NEW v07: Use collectCells function
                def cells = collectCells(testROI, hierarchy, config)
                
                if (!cells.isEmpty()) {
                    if (config.debugMode) {
                        println "  Found ${cells.size()} cells in ROI at (${x.round(2)}, ${y.round(2)})"
                        
                        // Count cell types for debugging
                        def detectionCount = cells.count { it instanceof PathDetectionObject }
                        def pointAnnotationCount = cells.count { 
                            it instanceof PathAnnotationObject && it.getROI() instanceof PointsROI 
                        }
                        println "    - Detection objects: ${detectionCount}"
                        println "    - Point annotation objects: ${pointAnnotationCount}"
                    }
                    
                    tilMetrics = analyzeTILs(
                        cells,
                        intersectingTumor,
                        PathObjects.createAnnotationObject(testROI),
                        config.tilDistanceThresholdMicrons,
                        config.tilAdaptiveThresholdPercentile,
                        config,
                        hierarchy,
                        imageData,
                        stromaArea
                    )
                } else {
                    if (config.debugMode) {
                        println "  WARNING: No cells found in ROI at (${x.round(2)}, ${y.round(2)})"
                    }
                    // Initialize with zeros if no cells found
                    tilMetrics = [
                        TILs_count: 0,
                        Tumor_count: 0,
                        Stroma_count: 0,
                        Other_count: 0,
                        Total_count: 0,
                        eTILs_percent: 0.0,
                        etTILs_percent: 0.0,
                        esTILs_percent: 0.0,
                        eaTILs_per_mm2: 0.0,
                        easTILs_percent: 0.0,
                        TIL_area_mm2: 0.0,
                        Stroma_area_mm2: stromaArea * pixelWidth * pixelHeight / 1000000.0
                    ]
                }
            }
            
            def result = [
                roi: testROI,
                tsr: tsr,
                tissuePercentage: tissuePercentage,
                tumorPercentage: tumorPercentage,
                tumorArea: tumorArea,
                stromaArea: stromaArea,
                tissueArea: tissueArea,
                tilMetrics: tilMetrics
            ]
            
            validResults.add(result)
            
            // Create validation annotation if enabled
            if (config.showAlgorithmValidationAnnotations) {
                def annotation = PathObjects.createAnnotationObject(testROI)
                annotation.setPathClass(PathClass.fromString("PASS"))
                validationAnnotations.add(annotation)
            }
            
            processedCount.incrementAndGet()
            
            if (processedCount.get() % config.updateInterval == 0) {
                log "Processed: ${processedCount.get()}, Valid: ${validResults.size()}, Rejected: ${rejectedCount.get()}"
            }
            
            if (validResults.size() >= config.maxROIsPerImage) {
                return
            }
        }
    }
    
    // Process in batches
    if (config.useParallelProcessing) {
        def executor = Executors.newFixedThreadPool(config.batchParallelThreads)
        def futures = []
        
        // Capture imageData for parallel processing
        final def currentImageData = imageData
        final def currentServer = server
        
        positions.collate(config.batchSize).each { batch ->
            futures.add(executor.submit { 
                // Use captured imageData in parallel threads
                processBatch(batch) 
            })
        }
        
        futures.each { it.get() }
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.HOURS)
    } else {
        positions.collate(config.batchSize).each { batch ->
            processBatch(batch)
        }
    }
    
    log "=========================================="
    log "Analysis complete: ${validResults.size()} valid ROIs"
    log "Output Method: ${config.outputMethod}"
    if (config.outputMethod == "Annotation") {
        log "Will keep only MAX values per tissue region"
    } else if (config.outputMethod == "Detection") {
        log "Creating all circles as detections for heatmap"
    }
    if (config.showAlgorithmValidationAnnotations || config.debugMode) {
        log "Rejected ROIs: ${rejectedCount.get()}"
        log "Total processed: ${processedCount.get()}"
        if (config.minTumorAreaUm2 > 0) {
            log "★ 4-SIDE DETECTION ACTIVE: Min Tumor Area = ${config.minTumorAreaUm2} μm²"
            log "  → ALL 4 quadrants must have >= ${config.minTumorAreaUm2} μm² tumor"
            if (config.minTumorPercentage > 0 && config.useAndConditionForArea) {
                log "  → Combined with Min Tumor %: ${config.minTumorPercentage}% (AND condition)"
            } else if (config.minTumorPercentage == 0) {
                log "  → Using ONLY area criterion"
            }
        }
        if (config.useAdvancedDetection) {
            log "Advanced Detection: ENABLED (additional validation)"
            log "Methods: ${[config.useMethod1 ? 'Method1' : null, config.useMethod2 ? 'Method2' : null, config.useMethod3 ? 'Method3' : null].findAll{it != null}.join(', ')}"
        }
    }
    
    // Add diagnostic info if no valid ROIs found
    if (validResults.isEmpty()) {
        log "WARNING: No valid ROIs found!"
        log "Diagnostic information:"
        log "  - Total positions checked: ${positions.size()}"
        log "  - Total processed: ${processedCount.get()}"
        log "  - Total rejected: ${rejectedCount.get()}"
        log "  - Min tumor %: ${config.minTumorPercentage}%"
        log "  - Min tissue %: ${config.minTissuePercentage}%"
        if (config.minTumorAreaUm2 > 0) {
            log "  - ★ 4-SIDE DETECTION: Min tumor area = ${config.minTumorAreaUm2} μm²"
            log "    (ALL 4 quadrants must have >= ${config.minTumorAreaUm2} μm² tumor)"
        }
        log "  - Advanced Detection enabled: ${config.useAdvancedDetection}"
        log "Consider:"
        log "  - Reducing minimum tumor percentage"
        log "  - Reducing minimum tissue percentage"
        if (config.minTumorAreaUm2 > 0) {
            log "  - Reducing minimum tumor area threshold (currently very strict: all 4 sides required)"
            log "  - Or set MIN_TUMOR_AREA_UM2 = 0 to disable 4-side detection"
        }
        log "  - Disabling Advanced Detection"
        log "  - Checking tissue/tumor annotation overlap"
    }
    
    log "=========================================="
    
    // Create visual annotations based on output method
    if (config.createVisualAnnotations && !validResults.isEmpty()) {
        log "Creating visual annotations..."
        
        if (config.outputMethod == "Annotation") {
            // Annotation mode: Keep only MAX TSR and MAX easTILs per tissue region
            log "Output Method: Annotation - Creating best TSR circle per tissue region"
            
            // Re-fetch tissue annotations for grouping
            def tissueAnnotationsForGrouping = hierarchy.getAnnotationObjects().findAll {
                if (it.getROI() instanceof PointsROI) return false
                if (it.getROI().getArea() < 10000) return false
                def className = it.getPathClass()?.toString()
                return className?.equalsIgnoreCase(config.tissueClassName)
            }
            
            // Group results by tissue annotation
            def tissueGroups = [:]
            validResults.each { result ->
                // Find which tissue annotation this ROI belongs to
                def belongsToTissue = null
                tissueAnnotationsForGrouping.each { tissue ->
                    if (tissue.getROI().getGeometry().contains(result.roi.getGeometry())) {
                        belongsToTissue = tissue
                    }
                }
                
                if (belongsToTissue == null) {
                    // Check for intersection if not fully contained
                    tissueAnnotationsForGrouping.each { tissue ->
                        if (tissue.getROI().getGeometry().intersects(result.roi.getGeometry())) {
                            belongsToTissue = tissue
                        }
                    }
                }
                
                if (belongsToTissue != null) {
                    def tissueId = System.identityHashCode(belongsToTissue)
                    if (!tissueGroups.containsKey(tissueId)) {
                        tissueGroups[tissueId] = []
                    }
                    tissueGroups[tissueId].add(result)
                }
            }
            
            // Find MAX TSR and MAX easTILs for each tissue region
            def bestAnnotations = []
            tissueGroups.each { tissueId, results ->
                if (!results.isEmpty()) {
                    // Find MAX TSR
                    def maxTSRResult = results.max { it.tsr }
                    def annotation = PathObjects.createAnnotationObject(maxTSRResult.roi)
                    
                    // Set name with TSR value
                    def tsrValue = String.format("%.4f", maxTSRResult.tsr as double)
                    annotation.setName("TSR_MAX(${tsrValue})")
                    annotation.setPathClass(PathClass.fromString("TSR_MAX"))
                    
                    // Add measurements (QuPath v0.6: use put() instead of putMeasurement())
                    def measurements = annotation.getMeasurementList()
                    measurements.put("TSR", maxTSRResult.tsr as double)
                    measurements.put("Tissue_percent", maxTSRResult.tissuePercentage as double)
                    measurements.put("Tumor_percent", maxTSRResult.tumorPercentage as double)
                    measurements.put("Tumor_area_pixels", maxTSRResult.tumorArea as double)
                    measurements.put("Stroma_area_pixels", maxTSRResult.stromaArea as double)
                    measurements.put("Tissue_area_pixels", maxTSRResult.tissueArea as double)
                    
                    // Add metadata for direct viewing in annotation properties
                    def metadata = annotation.getMetadata()
                    metadata.put("TSR", String.format("%.4f", maxTSRResult.tsr as double))
                    metadata.put("Tissue_%", String.format("%.2f", maxTSRResult.tissuePercentage as double))
                    metadata.put("Tumor_%", String.format("%.2f", maxTSRResult.tumorPercentage as double))
                    metadata.put("Tumor_area_px", String.format("%.0f", maxTSRResult.tumorArea as double))
                    metadata.put("Stroma_area_px", String.format("%.0f", maxTSRResult.stromaArea as double))
                    metadata.put("Tissue_area_px", String.format("%.0f", maxTSRResult.tissueArea as double))
                    
                    if (config.enableTILAnalysis && maxTSRResult.tilMetrics != null) {
                        maxTSRResult.tilMetrics.each { key, value ->
                            measurements.put(key, value as double)
                            // Add key TIL metrics to metadata for easy viewing
                            if (key in ["TILs_count", "eTILs_percent", "easTILs_percent", "Stroma_TILs_per_mm2"]) {
                                metadata.put(key, String.format("%.2f", value as double))
                            }
                        }
                    }
                    
                    measurements.close()  // Close to finalize measurements and avoid duplicates
                    bestAnnotations.add(annotation)
                    
                    // Find MAX easTILs if TIL analysis is enabled
                    if (config.enableTILAnalysis) {
                        def maxEasTILResult = results.max { 
                            it.tilMetrics?.easTILs_percent ?: 0 
                        }
                        
                        // Only create separate annotation if it's different from MAX TSR
                        if (maxEasTILResult != maxTSRResult) {
                            def tilAnnotation = PathObjects.createAnnotationObject(maxEasTILResult.roi)
                            
                            // Set name with easTILs_percent value
                            def easTILValue = maxEasTILResult.tilMetrics?.easTILs_percent ?: 0.0
                            tilAnnotation.setName("easTIL_MAX(${String.format('%.2f', easTILValue)}%)")
                            tilAnnotation.setPathClass(PathClass.fromString("easTIL_MAX"))
                            
                            // Add measurements (QuPath v0.6: use put() instead of putMeasurement())
                            def tilMeasurements = tilAnnotation.getMeasurementList()
                            tilMeasurements.put("TSR", maxEasTILResult.tsr as double)
                            tilMeasurements.put("Tissue_percent", maxEasTILResult.tissuePercentage as double)
                            tilMeasurements.put("Tumor_percent", maxEasTILResult.tumorPercentage as double)
                            
                            // Add metadata for direct viewing in annotation properties
                            def tilMetadata = tilAnnotation.getMetadata()
                            tilMetadata.put("TSR", String.format("%.4f", maxEasTILResult.tsr as double))
                            tilMetadata.put("Tissue_%", String.format("%.2f", maxEasTILResult.tissuePercentage as double))
                            tilMetadata.put("Tumor_%", String.format("%.2f", maxEasTILResult.tumorPercentage as double))
                            
                            if (maxEasTILResult.tilMetrics != null) {
                                maxEasTILResult.tilMetrics.each { key, value ->
                                    tilMeasurements.put(key, value as double)
                                    // Add key TIL metrics to metadata for easy viewing
                                    if (key in ["TILs_count", "eTILs_percent", "easTILs_percent", "Stroma_TILs_per_mm2"]) {
                                        tilMetadata.put(key, String.format("%.2f", value as double))
                                    }
                                }
                            }
                            
                            tilMeasurements.close()  // Close to finalize measurements and avoid duplicates
                            bestAnnotations.add(tilAnnotation)
                        }
                    }
                }
            }
            
            // Clear existing TSR and easTIL annotations
            def existingAnnotations = hierarchy.getAnnotationObjects().findAll {
                def className = it.getPathClass()?.toString()
                return className in ["TSR_MAX", "easTIL_MAX", "TSR", config.resultClassName, "PASS", "FAIL_ADVANCED"]
            }
            hierarchy.removeObjects(existingAnnotations, true)
            
            // Add best annotations (QuPath v0.6: use addObjects() instead of addPathObjects())
            hierarchy.addObjects(bestAnnotations)
            
            log "Created ${bestAnnotations.size()} best annotations"
            
        } else if (config.outputMethod == "Detection") {
            // Detection mode: Create all circles as detections
            log "Output Method: Detection - Creating ${validResults.size()} detection objects"
            
            def detections = []
            validResults.each { result ->
                def detection = PathObjects.createDetectionObject(result.roi)
                detection.setPathClass(PathClass.fromString(config.resultClassName))
                
                // Add measurements (QuPath v0.6: use put() instead of putMeasurement())
                def measurements = detection.getMeasurementList()
                measurements.put("TSR", result.tsr as double)
                measurements.put("Tissue_percent", result.tissuePercentage as double)
                measurements.put("Tumor_percent", result.tumorPercentage as double)
                
                if (config.enableTILAnalysis && result.tilMetrics != null) {
                    result.tilMetrics.each { key, value ->
                        measurements.put(key, value as double)
                    }
                }
                
                measurements.close()  // Close to finalize measurements and avoid duplicates
                detections.add(detection)
            }
            
            // Clear existing TSR detections and annotations
            def existingObjects = []
            existingObjects.addAll(hierarchy.getDetectionObjects().findAll {
                it.getPathClass()?.toString() == config.resultClassName
            })
            existingObjects.addAll(hierarchy.getAnnotationObjects().findAll {
                def className = it.getPathClass()?.toString()
                return className in ["TSR_MAX", "easTIL_MAX", "TSR", config.resultClassName, "PASS", "FAIL_ADVANCED"]
            })
            hierarchy.removeObjects(existingObjects, true)
            
            // Add new detections (QuPath v0.6: use addObjects() instead of addPathObjects())
            hierarchy.addObjects(detections)
            
            log "Created ${detections.size()} detection objects with TSR values"
            log "Use 'Measure' -> 'Show measurements' -> 'Show measurement maps' to visualize heatmap"
        }
        
        // Add validation annotations if enabled
        if (config.showAlgorithmValidationAnnotations && !validationAnnotations.isEmpty()) {
            hierarchy.addObjects(validationAnnotations)
            log "Added ${validationAnnotations.size()} validation annotations"
        }
    }
    
    // Export results
    exportResultsToTSV(validResults, config, imageData, startTime)
    
    // Clean up
    if (tissueCache != null) {
        tissueCache.clear()
    }
    
    hierarchy.fireHierarchyChangedEvent(this)
    log "TSR & TIL Integrated Analysis complete!"
}

// =====================================================
// EXPORT RESULTS TO TSV
// =====================================================

void exportResultsToTSV(validResults, config, imageData, startTime) {
    def server = imageData.getServer()
    def imageName = server.getMetadata().getName()
    
    // Sanitize image name for Windows file system - remove problematic characters
    // Replace: < > : " / \ | ? * , with underscore
    def sanitizedImageName = imageName.replaceAll(/[<>:\"\/\\|?*,]/, '_')
    
    // Get or create project directory
    def project = getProject()
    def projectDir = null
    
    if (project != null) {
        def projectPath = project.getPath()
        if (projectPath != null) {
            def projectFile = projectPath.toFile()
            // If project path points to a .qpproj file, use its parent directory
            if (projectFile.isFile() && projectFile.getName().endsWith('.qpproj')) {
                projectDir = projectFile.getParentFile()
            } else if (projectFile.isDirectory()) {
                projectDir = projectFile
            } else {
                // Use parent directory as fallback
                projectDir = projectFile.getParentFile()
            }
        }
    }
    
    // Fallback to user home if project directory not found
    if (projectDir == null || !projectDir.exists()) {
        projectDir = new File(System.getProperty("user.home"), "QuPath_Results")
        println "Using fallback directory: ${projectDir.absolutePath}"
    }
    
    println "Project directory: ${projectDir.absolutePath}"
    
    // Create export folder WITHOUT timestamp (use EXPORT_FOLDER as-is)
    def exportDir = new File(projectDir, config.exportFolder)
    
    // Ensure directory exists
    if (!exportDir.exists()) {
        def created = exportDir.mkdirs()
        if (!created) {
            println "ERROR: Failed to create export directory: ${exportDir.absolutePath}"
            return
        }
    }
    println "Export directory: ${exportDir.absolutePath}"
    
    // Create TSV file with mode (Detection/Annotation) and optional timestamp in filename
    def outputMode = config.outputMethod  // "Detection" or "Annotation"
    def timestampStr = ""
    if (config.includeTimestamp) {
        timestampStr = "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    }
    def tsvFile = new File(exportDir, "${sanitizedImageName.replaceAll(/\.[^\.]+$/, '')}_${outputMode}${timestampStr}_TSR_TIL_results.tsv")
    
    // Write header and data
    tsvFile.withWriter { writer ->
        def tab = "\t"
        
        // Write header
        def header = ["Image_Name", "ROI_Count", "Analysis_Mode"]
        
        // TSR Statistics
        header.addAll(["TSR_MAX", "TSR_MEAN", "TSR_MIN", "TSR_MEDIAN", "TSR_SD"])
        header.addAll(["Tissue_Percent_MEAN", "Tumor_Percent_MEAN"])
        
        // TIL Statistics (if enabled)
        if (config.enableTILAnalysis) {
            header.addAll([
                "TIL_Count_MAX", "TIL_Count_MEAN", "TIL_Count_MIN", "TIL_Count_MEDIAN", "TIL_Count_SD",
                "Tumor_Cell_Count_MEAN", "Stroma_Cell_Count_MEAN", "Other_Cell_Count_MEAN",
                "eTILs_Percent_MAX", "eTILs_Percent_MEAN", "eTILs_Percent_MIN", "eTILs_Percent_MEDIAN", "eTILs_Percent_SD",
                "etTILs_Percent_MAX", "etTILs_Percent_MEAN", "etTILs_Percent_MIN", "etTILs_Percent_MEDIAN", "etTILs_Percent_SD",
                "esTILs_Percent_MAX", "esTILs_Percent_MEAN", "esTILs_Percent_MIN", "esTILs_Percent_MEDIAN", "esTILs_Percent_SD",
                "eaTILs_per_mm2_MAX", "eaTILs_per_mm2_MEAN", "eaTILs_per_mm2_MIN", "eaTILs_per_mm2_MEDIAN", "eaTILs_per_mm2_SD",
                "easTILs_Percent_MAX", "easTILs_Percent_MEAN", "easTILs_Percent_MIN", "easTILs_Percent_MEDIAN", "easTILs_Percent_SD",
                "TIL_Area_mm2_MAX", "TIL_Area_mm2_MEAN", "TIL_Area_mm2_MIN", "TIL_Area_mm2_MEDIAN", "TIL_Area_mm2_SD",
                "Stroma_Area_mm2_MAX", "Stroma_Area_mm2_MEAN", "Stroma_Area_mm2_MIN", "Stroma_Area_mm2_MEDIAN", "Stroma_Area_mm2_SD"
            ])
        }
        
        // Algorithm Parameters
        header.addAll([
            "Valid_ROIs", "Output_Method", "Circle_Diameter_mm", "Overlap_Ratio",
            "Min_Tumor_Percent", "Min_Tissue_Percent",
            "TIL_Analysis_Enabled", "TIL_Analysis_Mode",
            "Avg_TIL_Diameter_um", "Avg_Tumor_Cell_Diameter_um", "Avg_Stroma_Cell_Diameter_um",
            "Tissue_Detection_Method", "Performance_Mode", "Advanced_Detection"
        ])
        
        // Advanced Detection Parameters (if enabled)
        if (config.useAdvancedDetection) {
            header.addAll([
                "Method1", "Method2", "Method3", "Central_Check",
                "Peripheral_Distance_Ratio", "Ellipse_Radius_Ratio",
                "Central_Radius_Ratio", "Central_Concentration_Limit",
                "Ring_Inner_Ratio", "Ring_Outer_Ratio",
                "Radial_Inner_Ratio", "Radial_Outer_Ratio",
                "Required_Ring_Sectors", "Required_Radial_Directions",
                "Min_Tumor_Area_um2", "Area_Condition"
            ])
        }
        
        header.addAll([
            "Tissue_Threshold", "Downsample",
            "Parallel_Processing", "Thread_Count", "Processing_Time_s"
        ])
        
        writer.writeLine(header.join(tab))
        
        // Write data row
        def row = []
        row.add(imageName)
        row.add(validResults.size().toString())
        row.add(config.tilAnalysisMode)
        
        // Calculate and add statistics
        if (!validResults.isEmpty()) {
            // TSR Statistics
            def tsrValues = validResults.collect { it.tsr }
            def tsrMax = tsrValues.max()
            def tsrMin = tsrValues.min()
            def tsrMean = tsrValues.sum() / tsrValues.size()
            def tsrMedian = calculateMedian(tsrValues)
            def tsrSD = Math.sqrt(tsrValues.collect { (it - tsrMean) ** 2 }.sum() / tsrValues.size())
            
            row.add(String.format("%.4f", tsrMax))
            row.add(String.format("%.4f", tsrMean))
            row.add(String.format("%.4f", tsrMin))
            row.add(String.format("%.4f", tsrMedian))
            row.add(String.format("%.4f", tsrSD))
            
            // Tissue and Tumor percentages
            def tissuePercents = validResults.collect { it.tissuePercentage }
            def tumorPercents = validResults.collect { it.tumorPercentage }
            row.add(String.format("%.2f", tissuePercents.sum() / tissuePercents.size()))
            row.add(String.format("%.2f", tumorPercents.sum() / tumorPercents.size()))
            
            // TIL Statistics (if enabled)
            if (config.enableTILAnalysis && validResults[0].tilMetrics != null) {
                // TIL Count
                def tilCounts = validResults.collect { it.tilMetrics.TILs_count as Double }
                def tilMean = tilCounts.sum() / tilCounts.size()
                row.add(tilCounts.max().round().toString())
                row.add(String.format("%.2f", tilMean))
                row.add(tilCounts.min().round().toString())
                row.add(String.format("%.0f", calculateMedian(tilCounts)))
                row.add(String.format("%.2f", Math.sqrt(tilCounts.collect { (it - tilMean) ** 2 }.sum() / tilCounts.size())))
                
                // Other cell counts
                def tumorCellCounts = validResults.collect { it.tilMetrics.Tumor_count as Double }
                def stromaCellCounts = validResults.collect { it.tilMetrics.Stroma_count as Double }
                def otherCellCounts = validResults.collect { it.tilMetrics.Other_count as Double }
                row.add(String.format("%.2f", tumorCellCounts.sum() / tumorCellCounts.size()))
                row.add(String.format("%.2f", stromaCellCounts.sum() / stromaCellCounts.size()))
                row.add(String.format("%.2f", otherCellCounts.sum() / otherCellCounts.size()))
                
                // eTILs percent
                def eTILsPercents = validResults.collect { it.tilMetrics.eTILs_percent as Double }
                def eTILsMean = eTILsPercents.sum() / eTILsPercents.size()
                row.add(String.format("%.2f", eTILsPercents.max()))
                row.add(String.format("%.2f", eTILsMean))
                row.add(String.format("%.2f", eTILsPercents.min()))
                row.add(String.format("%.2f", calculateMedian(eTILsPercents)))
                row.add(String.format("%.2f", Math.sqrt(eTILsPercents.collect { (it - eTILsMean) ** 2 }.sum() / eTILsPercents.size())))
                
                // etTILs percent
                def etTILsPercents = validResults.collect { it.tilMetrics.etTILs_percent as Double }
                def etTILsMean = etTILsPercents.sum() / etTILsPercents.size()
                row.add(String.format("%.2f", etTILsPercents.max()))
                row.add(String.format("%.2f", etTILsMean))
                row.add(String.format("%.2f", etTILsPercents.min()))
                row.add(String.format("%.2f", calculateMedian(etTILsPercents)))
                row.add(String.format("%.2f", Math.sqrt(etTILsPercents.collect { (it - etTILsMean) ** 2 }.sum() / etTILsPercents.size())))
                
                // esTILs percent
                def esTILsPercents = validResults.collect { it.tilMetrics.esTILs_percent as Double }
                def esTILsMean = esTILsPercents.sum() / esTILsPercents.size()
                row.add(String.format("%.2f", esTILsPercents.max()))
                row.add(String.format("%.2f", esTILsMean))
                row.add(String.format("%.2f", esTILsPercents.min()))
                row.add(String.format("%.2f", calculateMedian(esTILsPercents)))
                row.add(String.format("%.2f", Math.sqrt(esTILsPercents.collect { (it - esTILsMean) ** 2 }.sum() / esTILsPercents.size())))
                
                // eaTILs per mm2
                def eaTILsPerMm2 = validResults.collect { it.tilMetrics.eaTILs_per_mm2 as Double }
                def eaTILsMean = eaTILsPerMm2.sum() / eaTILsPerMm2.size()
                row.add(String.format("%.2f", eaTILsPerMm2.max()))
                row.add(String.format("%.2f", eaTILsMean))
                row.add(String.format("%.2f", eaTILsPerMm2.min()))
                row.add(String.format("%.2f", calculateMedian(eaTILsPerMm2)))
                row.add(String.format("%.2f", Math.sqrt(eaTILsPerMm2.collect { (it - eaTILsMean) ** 2 }.sum() / eaTILsPerMm2.size())))
                
                // easTILs percent
                def easTILsPercents = validResults.collect { it.tilMetrics.easTILs_percent as Double }
                def easTILsMean = easTILsPercents.sum() / easTILsPercents.size()
                row.add(String.format("%.2f", easTILsPercents.max()))
                row.add(String.format("%.2f", easTILsMean))
                row.add(String.format("%.2f", easTILsPercents.min()))
                row.add(String.format("%.2f", calculateMedian(easTILsPercents)))
                row.add(String.format("%.2f", Math.sqrt(easTILsPercents.collect { (it - easTILsMean) ** 2 }.sum() / easTILsPercents.size())))
                
                // TIL Area mm2
                def tilAreaMm2 = validResults.collect { it.tilMetrics.TIL_area_mm2 as Double }
                def tilAreaMean = tilAreaMm2.sum() / tilAreaMm2.size()
                row.add(String.format("%.6f", tilAreaMm2.max()))
                row.add(String.format("%.6f", tilAreaMean))
                row.add(String.format("%.6f", tilAreaMm2.min()))
                row.add(String.format("%.6f", calculateMedian(tilAreaMm2)))
                row.add(String.format("%.6f", Math.sqrt(tilAreaMm2.collect { (it - tilAreaMean) ** 2 }.sum() / tilAreaMm2.size())))
                
                def stromaAreaMm2 = validResults.collect { it.tilMetrics.Stroma_area_mm2 as Double }
                def stromaAreaMean = stromaAreaMm2.sum() / stromaAreaMm2.size()
                row.add(String.format("%.6f", stromaAreaMm2.max()))
                row.add(String.format("%.6f", stromaAreaMean))
                row.add(String.format("%.6f", stromaAreaMm2.min()))
                row.add(String.format("%.6f", calculateMedian(stromaAreaMm2)))
                row.add(String.format("%.6f", Math.sqrt(stromaAreaMm2.collect { (it - stromaAreaMean) ** 2 }.sum() / stromaAreaMm2.size())))
            }
        }
        
        // Algorithm Parameters
        row.add(validResults.size().toString())
        row.add(config.outputMethod)
        row.add(String.format("%.1f", config.circleDiameterMm))
        row.add(String.format("%.2f", config.overlapRatio))
        row.add(String.format("%.2f", config.minTumorPercentage))
        row.add(String.format("%.2f", config.minTissuePercentage))
        row.add(config.enableTILAnalysis.toString())
        row.add(config.tilAnalysisMode)
        row.add(String.format("%.1f", config.averageTILDiameterMicrons))
        row.add(String.format("%.1f", config.averageTumorCellDiameterMicrons))
        row.add(String.format("%.1f", config.averageStromaCellDiameterMicrons))
        row.add(config.tissueDetectionMethod)
        row.add(config.fastApproximation ? "Fast_Approximation" : "Standard")
        row.add(config.useAdvancedDetection.toString())
        
        // Advanced Detection Parameters (if enabled)
        if (config.useAdvancedDetection) {
            row.add(config.useMethod1.toString())
            row.add(config.useMethod2.toString())
            row.add(config.useMethod3.toString())
            row.add(config.useCentralConcentrationCheck.toString())
            row.add(String.format("%.2f", config.peripheralDistanceRatio))
            row.add(String.format("%.2f", config.ellipseRadiusRatio))
            row.add(String.format("%.2f", config.centralRadiusRatio))
            row.add(String.format("%.2f", config.centralConcentrationLimit))
            row.add(String.format("%.2f", config.ringInnerRatio))
            row.add(String.format("%.2f", config.ringOuterRatio))
            row.add(String.format("%.2f", config.radialInnerRatio))
            row.add(String.format("%.2f", config.radialOuterRatio))
            row.add(config.requiredRingSectors.toString())
            row.add(config.requiredRadialDirections.toString())
            row.add(String.format("%.1f", config.minTumorAreaUm2))
            row.add(config.minTumorPercentage > 0 && config.minTumorAreaUm2 > 0 ? "AND" : "Single")
        }
        
        row.add(config.tissueThreshold.toString())
        row.add(String.format("%.1f", config.downsample))
        row.add(config.useParallelProcessing.toString())
        row.add(config.numThreads.toString())
        
        def endTime = System.currentTimeMillis()
        def elapsedSeconds = (endTime - startTime) / 1000.0
        row.add(String.format("%.2f", elapsedSeconds))
        
        writer.writeLine(row.join(tab))
    }
    
    println "Results exported to: ${tsvFile.absolutePath}"
    println "Format: Tab-separated values (TSV) with comprehensive statistics"
    
    // Also display statistics in console if enabled
    if (config.calculateStatistics && !validResults.isEmpty()) {
        println "=========================================="
        println "Statistics Summary:"
        println "=========================================="
        
        // Recalculate statistics for console output
        def tsrValuesForDisplay = validResults.collect { it.tsr }
        def tsrMaxDisplay = tsrValuesForDisplay.max()
        def tsrMinDisplay = tsrValuesForDisplay.min()
        def tsrMeanDisplay = tsrValuesForDisplay.sum() / tsrValuesForDisplay.size()
        def tsrMedianDisplay = calculateMedian(tsrValuesForDisplay)
        def tsrSDDisplay = Math.sqrt(tsrValuesForDisplay.collect { (it - tsrMeanDisplay) ** 2 }.sum() / tsrValuesForDisplay.size())
        
        println "TSR - MAX: ${String.format('%.4f', tsrMaxDisplay)}, MEAN: ${String.format('%.4f', tsrMeanDisplay)}, MEDIAN: ${String.format('%.4f', tsrMedianDisplay)}, MIN: ${String.format('%.4f', tsrMinDisplay)}, SD: ${String.format('%.4f', tsrSDDisplay)}"
        
        if (config.enableTILAnalysis && validResults[0].tilMetrics != null) {
            def tilCounts = validResults.collect { it.tilMetrics.TILs_count as Double }
            def tilMean = tilCounts.sum() / tilCounts.size()
            def tilMedian = calculateMedian(tilCounts)
            println "TIL Count - MAX: ${tilCounts.max().round()}, MEAN: ${tilMean.round(2)}, MEDIAN: ${tilMedian.round()}, MIN: ${tilCounts.min().round()}"
            
            def eTILsPercents = validResults.collect { it.tilMetrics.eTILs_percent as Double }
            def eTILsMean = eTILsPercents.sum() / eTILsPercents.size()
            def eTILsMedian = calculateMedian(eTILsPercents)
            println "eTILs % - MAX: ${String.format('%.2f', eTILsPercents.max())}, MEAN: ${String.format('%.2f', eTILsMean)}, MEDIAN: ${String.format('%.2f', eTILsMedian)}, MIN: ${String.format('%.2f', eTILsPercents.min())}"
            
            def easTILsPercents = validResults.collect { it.tilMetrics.easTILs_percent as Double }
            def easTILsMean = easTILsPercents.sum() / easTILsPercents.size()
            def easTILsMedian = calculateMedian(easTILsPercents)
            println "easTILs % (area-based) - MAX: ${String.format('%.2f', easTILsPercents.max())}, MEAN: ${String.format('%.2f', easTILsMean)}, MEDIAN: ${String.format('%.2f', easTILsMedian)}, MIN: ${String.format('%.2f', easTILsPercents.min())}"
            
            if (validResults[0].tilMetrics.containsKey('TIL_area_mm2')) {
                println "  Note: easTILs calculated using virtual cell areas (TIL diameter: ${config.averageTILDiameterMicrons} µm)"
            }
        }
        
        println "----------------------------------------"
        println "Total valid ROIs analyzed: ${validResults.size()}"
        if (config.outputMethod == "Annotation") {
            println "Output: Best annotations (TSR_MAX${config.enableTILAnalysis ? ' + easTIL_MAX' : ''}) per tissue region"
        } else if (config.outputMethod == "Detection") {
            println "Output: ${validResults.size()} detection objects for heatmap"
        }
    } else if (validResults.isEmpty()) {
        println "=========================================="
        println "Statistics Summary:"
        println "=========================================="
        println "No valid ROIs found - no statistics available"
        println "Possible reasons:"
        println "  - Min Tumor %: ${config.minTumorPercentage}% may be too high"
        println "  - Min Tissue %: ${config.minTissuePercentage}% may be too high"
        if (config.minTumorAreaUm2 > 0) {
            println "  - ★ 4-SIDE DETECTION: Min Tumor Area = ${config.minTumorAreaUm2} μm²"
            println "    This is VERY STRICT - ALL 4 quadrants must have >= ${config.minTumorAreaUm2} μm² tumor"
            println "    Consider reducing this value or setting to 0 to disable"
            if (config.minTumorPercentage == 0) {
                println "    (Currently using ONLY area criterion since Min Tumor % = 0)"
            }
        }
        if (config.useAdvancedDetection) {
            println "  - Advanced Detection is filtering all ROIs"
            println "    Consider disabling Advanced Detection or adjusting its parameters"
        }
        println "  - Circle diameter (${config.circleDiameterMm} mm) may not match tissue size"
        println "  - Check tissue and tumor annotations exist and have correct class names"
    }
}

// =====================================================
// RUN ANALYSIS
// =====================================================

runIntegratedAnalysis(config)

println "TSR & TIL Analysis completed!"
println "Output Method: ${OUTPUT_METHOD}"
if (OUTPUT_METHOD == "Annotation") {
    println "Created best TSR and easTIL circles per tissue region"
} else if (OUTPUT_METHOD == "Detection") {
    println "Created all circles as detections"
    println "Use 'Measure' -> 'Show measurements' -> 'Show measurement maps' to visualize TSR heatmap"
}
println "This is a single-image processing script."
println "Use 'Run for Project' in QuPath to apply this script to multiple images."
println "Each image will be processed and saved independently."
