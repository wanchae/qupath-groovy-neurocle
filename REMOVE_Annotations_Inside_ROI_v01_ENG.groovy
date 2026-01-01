/**
 * Selective Annotation Inside Removal Script
 *
 * For specified TARGET_ANNOTATION_CLASSES, removes the Inside area within ROI
 * and keeps only the Outside area beyond ROI
 *
 * Usage:
 * 1) Specify annotation classes to process in TARGET_ANNOTATION_CLASSES list
 * 2) Draw ROI annotation and set its class to match ROI_CLASS_NAME (default: "ROI")
 * 3) Run the script
 *
 * Result:
 * - Inside ROI area is removed from target class annotations, keeping only Outside portions
 * - If completely overlapping with ROI, the annotation is deleted as no area remains
 */

import qupath.lib.objects.classes.PathClassFactory
import java.awt.geom.Area
import org.locationtech.jts.geom.Geometry
import qupath.lib.common.GeneralTools
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.roi.ROIs

// =================================================================
// Configuration Section
// =================================================================

// Class name for ROI annotation
String ROI_CLASS_NAME = 'ROI'

// Target annotation class names (targets to remove inside ROI)
def TARGET_ANNOTATION_CLASSES = [
    'Tumor_HR',
    'Tumor',
    'Tumor_LR'
]

// Whether to merge same classes after processing
boolean MERGE_AFTER_PROCESS = true

// =================================================================
// Main Processing Logic
// =================================================================

resolveHierarchy()

def hierarchy = getCurrentHierarchy()
def annotations = getAnnotationObjects()

// Collect ROI annotations
def roiAnnotations = []
println("=== Finding ROI Annotations ===")
annotations.each { annotation ->
    def pathClass = annotation.getPathClass()
    if (pathClass != null && pathClass.getName() == ROI_CLASS_NAME) {
        roiAnnotations.add(annotation)
        println("ROI found: ${annotation}")
    }
}

if (roiAnnotations.isEmpty()) {
    println("WARNING: No ROI annotation ('${ROI_CLASS_NAME}') found! Please draw ROI first and run again.")
    return
}

println("Total ${roiAnnotations.size()} ROI annotation(s) found")

// Merge multiple ROIs into a single geometry
def combinedRoiGeometry = null
roiAnnotations.each { roi ->
    def roiGeom = roi.getROI().getGeometry()
    combinedRoiGeometry = (combinedRoiGeometry == null) ? roiGeom : combinedRoiGeometry.union(roiGeom)
}

println("\n=== Starting Target Annotation Processing (Remove Inside ROI / Keep Outside Only) ===")
println("Target classes: ${TARGET_ANNOTATION_CLASSES}")

def processedCount = 0
def skippedCount = 0
def deletedCount = 0

annotations.each { annotation ->
    def pathClass = annotation.getPathClass()

    // Skip ROI annotations
    if (pathClass != null && pathClass.getName() == ROI_CLASS_NAME)
        return

    // Check if target class
    def className = pathClass ? pathClass.getName() : null
    if (className == null || !TARGET_ANNOTATION_CLASSES.contains(className)) {
        skippedCount++
        return
    }

    println("\nProcessing: ${className} - ${annotation}")

    // Temporarily unlock if locked
    def wasLocked = annotation.isLocked()
    if (wasLocked) {
        annotation.setLocked(false)
        println("  -> Lock temporarily released")
    }

    try {
        def annotationGeometry = annotation.getROI().getGeometry()

        // Difference: annotation - ROI (remove inside ROI portion)
        def differenceGeometry = annotationGeometry.difference(combinedRoiGeometry)

        if (differenceGeometry.isEmpty()) {
            // ROI covers entire annotation or no outside area remains → delete
            println("  -> No outside area remains after removing inside ROI - Deleting")
            removeObjects([annotation], true)
            deletedCount++
        } else {
            // Keep only outside portion
            def plane = annotation.getROI().getImagePlane()
            def diffRoi = GeometryTools.geometryToROI(differenceGeometry, plane)
            def newAnno = PathObjects.createAnnotationObject(diffRoi, pathClass)

            // Restore properties like name/lock
            if (annotation.getName() != null)
                newAnno.setName(annotation.getName())
            if (wasLocked)
                newAnno.setLocked(true)

            // Replace
            hierarchy.removeObject(annotation, true)
            hierarchy.addObject(newAnno)

            println("  -> Inside ROI removed, outside portion kept (Lock restored: ${wasLocked})")
            processedCount++
        }
    } catch (Exception e) {
        println("  -> Error occurred: ${e.getMessage()}")
        if (wasLocked) annotation.setLocked(true)
    }
}

fireHierarchyUpdate()

// Merge (optional)
if (MERGE_AFTER_PROCESS && processedCount > 0) {
    println("\n=== Starting Merge of Same Classes ===")
    TARGET_ANNOTATION_CLASSES.each { className ->
        def classAnnotations = getAnnotationObjects().findAll {
            it.getPathClass()?.getName() == className
        }
        if (classAnnotations.size() > 1) {
            println("Merging '${className}' class... (${classAnnotations.size()} annotations)")
            // Merge is handled internally by QuPath when selected (unlock here if needed)
            selectObjectsByClassification(className)
            mergeSelectedAnnotations()
            resetSelection()
            println("  -> Merge complete")
        }
    }
}

// Result summary
println("\n" + "="*50)
println("=== Processing Complete (Inside Deleted / Outside Kept) ===")
println("="*50)
println("ROI annotation count: ${roiAnnotations.size()}")
println("Processed annotation count: ${processedCount}")
println("Deleted annotation count: ${deletedCount}")
println("Skipped annotation count: ${skippedCount}")
println("\nTarget classes: ${TARGET_ANNOTATION_CLASSES}")
println("Non-target classes: Region*, Tissue, and all other unspecified classes")
println("="*50)

// (Optional) Delete ROI after use
selectObjectsByClassification(ROI_CLASS_NAME)
removeSelectedObjects()
