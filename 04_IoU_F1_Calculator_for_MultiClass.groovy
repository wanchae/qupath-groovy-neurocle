import qupath.lib.objects.PathCellObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import java.util.Locale
import java.text.SimpleDateFormat

// ========== PARAMETERS - MODIFY THESE VALUES ==========

// Ground Truth Classes - can be single or multiple classes
// If multiple classes, they will be combined into one GT
//def groundTruthClasses = ["groundTruth"]  // Single class example
def groundTruthClasses = ["GT1", "GT2"]  // Multiple classes example

// Combined GT name (used when multiple GT classes are specified)
def combinedGTName = "GT12"  // This name will appear in CSV when multiple GT classes are combined

// Target Classes - can be single or multiple classes
//def targetClasses = ["target"]  // Single class example
def targetClasses = ["target1", "target2", "target3"]  // Multiple classes example

// Combined Target name (used when multiple target classes are specified)
def combinedTargetName = "target123"  // This name will appear in CSV when multiple target classes are combined

// Option to process target classes individually (false) or combined (true)
def combineTargetClasses = true  // Set to true to combine all target classes into one

// =======================================================

def metadata = getCurrentImageData().getServer().getOriginalMetadata()
def pixelSize = metadata.pixelCalibration.getPixelWidth()

// Get all annotation objects
def annotations = getAnnotationObjects()

// Process Ground Truth Classes
print("Processing Ground Truth classes: ${groundTruthClasses}")

// Filter annotations with Ground Truth classes
def tumorAnnotations = []
groundTruthClasses.each { gtClass ->
    def classAnnotations = annotations.findAll { it.getPathClass() != null && it.getPathClass().toString() == gtClass }
    tumorAnnotations.addAll(classAnnotations)
    print("Found ${classAnnotations.size()} annotations for GT class: ${gtClass}")
}

// Check if Ground Truth annotations exist
if (tumorAnnotations.isEmpty()) {
    print("No annotations found with Ground Truth classes: ${groundTruthClasses}")
    return
}

// Merge Ground Truth annotations if needed
def mergedTumorAnnotation = null
def gtDisplayName = groundTruthClasses.size() == 1 ? groundTruthClasses[0] : combinedGTName

if (tumorAnnotations.size() > 1 || groundTruthClasses.size() > 1) {
    print("Combining ${tumorAnnotations.size()} Ground Truth annotations...")
    
    // Create combined geometry without modifying actual annotations
    def combinedGeometry = tumorAnnotations[0].getROI().getGeometry()
    for (int i = 1; i < tumorAnnotations.size(); i++) {
        combinedGeometry = combinedGeometry.union(tumorAnnotations[i].getROI().getGeometry())
    }
    
    // Create a temporary annotation object for calculation
    def tempROI = GeometryTools.geometryToROI(combinedGeometry, ImagePlane.getDefaultPlane())
    mergedTumorAnnotation = [
        getROI: { -> tempROI }, 
        getPathClass: { -> [toString: { -> gtDisplayName }] as Object }
    ]
} else {
    mergedTumorAnnotation = tumorAnnotations[0]
}

// Prepare results
def results = []

// Process Target Classes
if (combineTargetClasses && targetClasses.size() > 1) {
    // Combine all target classes into one
    print("\nCombining all target classes into one: ${targetClasses}")
    
    def allTargetAnnotations = []
    targetClasses.each { targetClass ->
        def classAnnotations = annotations.findAll { it.getPathClass() != null && it.getPathClass().toString() == targetClass }
        allTargetAnnotations.addAll(classAnnotations)
        print("Found ${classAnnotations.size()} annotations for target class: ${targetClass}")
    }
    
    if (allTargetAnnotations.isEmpty()) {
        print("No annotations found for any target classes")
        return
    }
    
    // Create combined geometry
    def combinedTargetGeometry = allTargetAnnotations[0].getROI().getGeometry()
    for (int i = 1; i < allTargetAnnotations.size(); i++) {
        combinedTargetGeometry = combinedTargetGeometry.union(allTargetAnnotations[i].getROI().getGeometry())
    }
    
    // Create temporary annotation for calculation
    def tempTargetROI = GeometryTools.geometryToROI(combinedTargetGeometry, ImagePlane.getDefaultPlane())
    def mergedTargetAnnotation = [
        getROI: { -> tempTargetROI },
        getPathClass: { -> [toString: { -> combinedTargetName }] as Object }
    ]
    
    // Calculate metrics for combined target
    calculateAndAddResults(mergedTumorAnnotation, mergedTargetAnnotation, 
                          gtDisplayName, groundTruthClasses.size(),
                          combinedTargetName, allTargetAnnotations.size(),
                          pixelSize, results)
    
} else {
    // Process each target class individually
    targetClasses.each { targetClass ->
        print("\nProcessing target class: ${targetClass}")
        
        // Find all annotations with current target class
        def targetAnnotations = annotations.findAll { it.getPathClass() != null && it.getPathClass().toString() == targetClass }
        
        // Check if target annotations exist
        if (targetAnnotations.isEmpty()) {
            print("No annotations found with target class: ${targetClass}")
            return
        }
        
        print("Found ${targetAnnotations.size()} annotations for target class: ${targetClass}")
        
        // Merge target annotations if multiple exist
        def mergedTargetAnnotation = null
        if (targetAnnotations.size() > 1) {
            print("Combining ${targetAnnotations.size()} target annotations for class '${targetClass}'...")
            
            // Create a temporary merged geometry
            def combinedGeometry = targetAnnotations[0].getROI().getGeometry()
            for (int i = 1; i < targetAnnotations.size(); i++) {
                combinedGeometry = combinedGeometry.union(targetAnnotations[i].getROI().getGeometry())
            }
            
            // Create a temporary annotation object for calculation
            def tempROI = GeometryTools.geometryToROI(combinedGeometry, ImagePlane.getDefaultPlane())
            mergedTargetAnnotation = [
                getROI: { -> tempROI }, 
                getPathClass: { -> targetAnnotations[0].getPathClass() }
            ]
        } else {
            mergedTargetAnnotation = targetAnnotations[0]
        }
        
        // Calculate metrics
        calculateAndAddResults(mergedTumorAnnotation, mergedTargetAnnotation,
                              gtDisplayName, tumorAnnotations.size(),
                              targetClass, targetAnnotations.size(),
                              pixelSize, results)
    }
}

// Sort results by image name to ensure consistent ordering
results.sort { it["Image"] }

print("\n=== Final Results Summary ===")
results.each { result ->
    print("${result["Image"]} - GT: ${result["GT"]} vs Target: ${result["Class"]}: IoU=${String.format('%.4f', result["IoU"])}, F1=${String.format('%.4f', result["F1"])}")
}

// Save results to CSV
saveResultsToCSV(results)

print("\n=== Analysis completed successfully! ===")
print("Ground Truth: ${gtDisplayName} (${tumorAnnotations.size()} annotations)")
print("Target classes processed: ${combineTargetClasses ? combinedTargetName : targetClasses.join(', ')}")
print("Total comparisons completed: ${results.size()}")

// ========== HELPER FUNCTIONS ==========

def calculateAndAddResults(gtAnnotation, targetAnnotation, gtName, gtCount, targetName, targetCount, pixelSize, results) {
    // Calculate IoU and F1 score
    def tumorGeo = gtAnnotation.getROI().getGeometry()
    def targetGeo = targetAnnotation.getROI().getGeometry()

    // Intersect
    def intersectGeo = tumorGeo.intersection(targetGeo)
    def intersectionArea = intersectGeo.getArea() * pixelSize * pixelSize / 1000000

    // Union
    def unionGeo = tumorGeo.union(targetGeo)
    def unionArea = unionGeo.getArea() * pixelSize * pixelSize / 1000000

    // Individual areas
    def tumorArea = tumorGeo.getArea() * pixelSize * pixelSize / 1000000
    def targetArea = targetGeo.getArea() * pixelSize * pixelSize / 1000000

    // IoU (Intersection over Union)
    def iouValue = unionArea > 0 ? intersectionArea / unionArea : 0

    // F1 Score (Dice Coefficient)
    def F1score = (tumorArea + targetArea) > 0 ? (2 * intersectionArea / (tumorArea + targetArea)) : 0
    
    // Precision and Recall for additional metrics
    def precision = targetArea > 0 ? intersectionArea / targetArea : 0
    def recall = tumorArea > 0 ? intersectionArea / tumorArea : 0

    // Debug print
    print("Calculated metrics:")
    print("  GT: ${gtName} (${gtCount} annotations)")
    print("  Target: ${targetName} (${targetCount} annotations)")
    print("  IoU: ${String.format('%.6f', iouValue)}")
    print("  F1: ${String.format('%.6f', F1score)}")

    // Add to results
    def currentImage = getProjectEntry().getImageName()
    def resultMap = [:]
    resultMap["Image"] = currentImage
    resultMap["GT"] = gtName
    resultMap["GT_Count"] = gtCount
    resultMap["Class"] = targetName
    resultMap["Target_Count"] = targetCount
    resultMap["IoU"] = iouValue
    resultMap["F1"] = F1score
    resultMap["Precision"] = precision
    resultMap["Recall"] = recall
    resultMap["GT_Area_mm2"] = tumorArea
    resultMap["Target_Area_mm2"] = targetArea
    resultMap["Intersection_Area_mm2"] = intersectionArea
    
    results << resultMap
}

def saveResultsToCSV(results) {
    // Create folder for results inside the project folder
    def resultsFolder = buildFilePath(PROJECT_BASE_DIR, 'Results_IoU_F1_Combined')
    mkdirs(resultsFolder)

    // Create filename with timestamp to avoid file lock issues
    def dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss")
    def timestamp = dateFormat.format(new Date())
    def imageName = getProjectEntry().getImageName().replaceAll("[^a-zA-Z0-9_-]", "_")
    def fileName = "results_${imageName}_${timestamp}.csv"
    def file = new File(resultsFolder, fileName)

    // Try to write results to file with error handling
    def maxRetries = 3
    def retryCount = 0
    def success = false

    while (!success && retryCount < maxRetries) {
        try {
            // Write results to file (overwrite mode to avoid append issues)
            file.withWriter('UTF-8') { writer ->
                // Write header
                writer.writeLine("Image,GT,GT_Count,Class,Target_Count,IoU,F1,Precision,Recall,GT_Area_mm2,Target_Area_mm2,Intersection_Area_mm2")
                
                // Write data with proper formatting
                results.each { result ->
                    // Ensure all values are properly formatted and escaped
                    def imageNameClean = result["Image"].toString().replaceAll("\"", "\"\"")
                    def gtClean = result["GT"].toString().replaceAll("\"", "\"\"")
                    def classClean = result["Class"].toString().replaceAll("\"", "\"\"")
                    
                    def formattedLine = [
                        "\"${imageNameClean}\"",
                        "\"${gtClean}\"", 
                        result["GT_Count"].toString(),
                        "\"${classClean}\"",
                        result["Target_Count"].toString(),
                        String.format(Locale.US, "%.6f", result["IoU"]),
                        String.format(Locale.US, "%.6f", result["F1"]),
                        String.format(Locale.US, "%.6f", result["Precision"]),
                        String.format(Locale.US, "%.6f", result["Recall"]),
                        String.format(Locale.US, "%.6f", result["GT_Area_mm2"]),
                        String.format(Locale.US, "%.6f", result["Target_Area_mm2"]),
                        String.format(Locale.US, "%.6f", result["Intersection_Area_mm2"])
                    ].join(",")
                    
                    writer.writeLine(formattedLine)
                }
            }
            success = true
            print("\n=== File Write Successful ===")
            print("Results successfully written to: ${file.getAbsolutePath()}")
            print("Total records written: ${results.size()}")
            
        } catch (IOException e) {
            retryCount++
            print("Attempt ${retryCount} failed: ${e.getMessage()}")
            
            if (retryCount < maxRetries) {
                print("Retrying in 2 seconds...")
                Thread.sleep(2000)
                // Try with a different filename if still failing
                fileName = "results_${imageName}_${timestamp}_retry${retryCount}.csv"
                file = new File(resultsFolder, fileName)
            } else {
                print("Failed to write file after ${maxRetries} attempts.")
                print("Please close any Excel files and try again, or check file permissions.")
                throw e
            }
        }
    }
}