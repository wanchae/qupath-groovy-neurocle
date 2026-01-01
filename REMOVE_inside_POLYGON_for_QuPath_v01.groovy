import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.interfaces.ROI
import qupath.lib.roi.GeometryTools
import org.locationtech.jts.geom.Geometry
import qupath.lib.regions.ImagePlane

// Get all annotations and selected annotations
def allAnnotations = getAnnotationObjects()
def selectedAnnotations = getSelectedObjects().findAll { it.isAnnotation() }

print "=== Starting Exclusion Script ==="
print "Total annotations found: ${allAnnotations.size()}"

// Check if user has selected annotations to use as exclusion zones
if (selectedAnnotations.isEmpty()) {
    print "ERROR: No annotations selected!"
    print "\n=== Instructions ==="
    print "1. Select one or more annotations to use as exclusion zones"
    print "2. Run this script again"
    print "3. The selected annotation(s) will be used to remove/exclude parts from all other annotations"
    print "\nAvailable annotations:"
    allAnnotations.each { 
        def className = it.getPathClass()?.getName() ?: 'Unclassified'
        def name = it.getName() ?: ''
        print "  - Class: ${className}${name ? ', Name: ' + name : ''}"
    }
    return
}

print "Using ${selectedAnnotations.size()} selected annotation(s) as exclusion zone(s)"
selectedAnnotations.each { annotation ->
    def className = annotation.getPathClass()?.getName() ?: 'Unclassified'
    def name = annotation.getName() ?: ''
    print "  - Exclusion zone: Class=${className}${name ? ', Name=' + name : ''}"
}

// Create a union of all selected exclusion geometries
def exclusionGeometries = selectedAnnotations.collect { annotation ->
    annotation.getROI().getGeometry()
}

// Combine all exclusion geometries into one
def combinedExclusionGeometry = exclusionGeometries[0]
for (int i = 1; i < exclusionGeometries.size(); i++) {
    combinedExclusionGeometry = combinedExclusionGeometry.union(exclusionGeometries[i])
}

print "\n=== Processing annotations ==="
print "Will remove parts INSIDE the selected exclusion zone(s)"

// Lists to track changes
def annotationsToRemove = []
def annotationsToAdd = []
def processedCount = 0
def keptCount = 0
def removedCount = 0
def excludedCount = 0

allAnnotations.each { annotation ->
    // Skip if this is one of the selected exclusion annotations
    if (selectedAnnotations.contains(annotation)) {
        print "SKIP: Exclusion zone annotation itself"
        keptCount++
        return
    }
    
    def className = annotation.getPathClass()?.getName() ?: "Unclassified"
    def pathClass = annotation.getPathClass()
    
    // Get the geometry of current annotation
    def annotationGeometry = annotation.getROI().getGeometry()
    
    processedCount++
    
    // Check relationship with exclusion zone
    try {
        if (combinedExclusionGeometry.contains(annotationGeometry)) {
            // Annotation is completely inside exclusion zone - remove it entirely
            annotationsToRemove.add(annotation)
            removedCount++
            print "REMOVE: ${className} - Completely inside exclusion zone"
            
        } else if (!annotationGeometry.intersects(combinedExclusionGeometry)) {
            // Annotation is completely outside exclusion zone - keep as is
            keptCount++
            print "KEEP: ${className} - Completely outside exclusion zone"
            
        } else {
            // Annotation crosses exclusion boundary - keep only the part OUTSIDE exclusion zone
            try {
                // Use difference to get the part outside the exclusion zone
                def difference = annotationGeometry.difference(combinedExclusionGeometry)
                
                if (difference != null && !difference.isEmpty()) {
                    // Get the plane from the original annotation's ROI
                    def originalROI = annotation.getROI()
                    def plane = ImagePlane.getPlane(originalROI.getZ(), originalROI.getT())
                    
                    // Handle multi-part geometries (if the difference creates multiple polygons)
                    def geometryCount = difference.getNumGeometries()
                    
                    if (geometryCount == 1) {
                        // Single geometry result
                        def newROI = GeometryTools.geometryToROI(difference, plane)
                        def newAnnotation = PathObjects.createAnnotationObject(newROI, pathClass)
                        
                        // Copy properties from original annotation
                        if (annotation.getName() != null) {
                            newAnnotation.setName(annotation.getName())
                        }
                        
                        annotationsToRemove.add(annotation)
                        annotationsToAdd.add(newAnnotation)
                        excludedCount++
                        print "EXCLUDE: ${className} - Created 1 annotation with part outside exclusion zone"
                        
                    } else {
                        // Multiple geometries result - create separate annotations for each part
                        print "EXCLUDE: ${className} - Split into ${geometryCount} parts"
                        
                        for (int i = 0; i < geometryCount; i++) {
                            def subGeometry = difference.getGeometryN(i)
                            if (!subGeometry.isEmpty()) {
                                def newROI = GeometryTools.geometryToROI(subGeometry, plane)
                                def newAnnotation = PathObjects.createAnnotationObject(newROI, pathClass)
                                
                                // Copy properties and add part number to name
                                if (annotation.getName() != null) {
                                    newAnnotation.setName(annotation.getName() + "_part" + (i+1))
                                } else {
                                    newAnnotation.setName("part" + (i+1))
                                }
                                
                                annotationsToAdd.add(newAnnotation)
                            }
                        }
                        
                        annotationsToRemove.add(annotation)
                        excludedCount++
                    }
                } else {
                    // If difference is empty, the entire annotation was inside exclusion zone
                    annotationsToRemove.add(annotation)
                    removedCount++
                    print "REMOVE: ${className} - Difference operation resulted in empty geometry"
                }
            } catch (Exception e) {
                print "ERROR: Failed to process ${className}: ${e.getMessage()}"
                keptCount++
                // On error, keep the original annotation to be safe
            }
        }
    } catch (Exception e) {
        print "ERROR: Failed to check relationship for ${className}: ${e.getMessage()}"
        keptCount++
    }
}

// Print summary
print "\n=== Summary ==="
print "Annotations processed: ${processedCount}"
print "Annotations kept unchanged: ${keptCount}"
print "Annotations completely removed: ${removedCount}"
print "Annotations with exclusion applied: ${excludedCount}"
print "New annotation parts to add: ${annotationsToAdd.size()}"

// Execute changes
if (!annotationsToRemove.isEmpty()) {
    print "\nRemoving ${annotationsToRemove.size()} annotations..."
    removeObjects(annotationsToRemove, false)
}

if (!annotationsToAdd.isEmpty()) {
    print "Adding ${annotationsToAdd.size()} excluded/split annotations..."
    addObjects(annotationsToAdd)
}

// Count final annotations
def finalAnnotations = getAnnotationObjects()
print "\n=== Final Result ==="
print "Started with: ${allAnnotations.size()} annotations"
print "Ended with: ${finalAnnotations.size()} annotations"

// List final annotations by class
def classCounts = [:]
finalAnnotations.each { annotation ->
    def className = annotation.getPathClass()?.getName() ?: "Unclassified"
    classCounts[className] = classCounts.getOrDefault(className, 0) + 1
}

print "\nFinal annotation counts by class:"
classCounts.each { className, count ->
    print "  ${className}: ${count}"
}

// Clear selection and update display
clearSelectedObjects()
fireHierarchyUpdate()

print "\n✔ Exclusion script completed successfully!"
print "Parts inside the selected exclusion zone(s) have been removed from all other annotations."