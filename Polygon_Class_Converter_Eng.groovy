/**
 * Polygon Class Converter
 * 
 * This script converts polygon annotations between different classification types in QuPath.
 * It allows users to select source and target classification types and converts all annotations
 * of the source type to the target type within the current image.
 * 
 * Usage:
 * 1. Run the script in QuPath
 * 2. Select the source classification type from the first dropdown
 * 3. Select the target classification type from the second dropdown
 * 4. Click OK to convert all matching annotations
 * 
 * @author Seoung Wan Chae
 * @date 2026-01-01
 */

import qupath.lib.objects.PathAnnotationObject
import qupath.lib.gui.dialogs.Dialogs

// Get the current image data
def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Error", "No image is currently open")
    return
}

// Get all available classification types from the project
def hierarchy = imageData.getHierarchy()
def allAnnotations = hierarchy.getAnnotationObjects()
def classificationTypes = allAnnotations.collect { it.getPathClass() }.unique().findAll { it != null }

if (classificationTypes.isEmpty()) {
    Dialogs.showErrorMessage("Error", "No classified annotations found in the current image")
    return
}

// Convert PathClass objects to string names for display
def classNames = classificationTypes.collect { it.getName() }

// Create dialog for user selection
def sourceClass = Dialogs.showChoiceDialog(
    "Select Source Classification",
    "Choose the classification type to convert FROM:",
    classNames,
    classNames[0]
)

if (sourceClass == null) {
    println "Operation cancelled by user"
    return
}

def targetClass = Dialogs.showChoiceDialog(
    "Select Target Classification", 
    "Choose the classification type to convert TO:",
    classNames,
    classNames[0]
)

if (targetClass == null) {
    println "Operation cancelled by user"
    return
}

// Prevent converting to the same class
if (sourceClass == targetClass) {
    Dialogs.showErrorMessage("Error", "Source and target classifications cannot be the same")
    return
}

// Find the PathClass objects corresponding to the selected names
def sourcePathClass = classificationTypes.find { it.getName() == sourceClass }
def targetPathClass = classificationTypes.find { it.getName() == targetClass }

// Convert annotations
def convertedCount = 0
def annotationsToConvert = allAnnotations.findAll { 
    it.getPathClass() == sourcePathClass 
}

annotationsToConvert.each { annotation ->
    annotation.setPathClass(targetPathClass)
    convertedCount++
}

// Update the hierarchy to reflect changes
hierarchy.fireHierarchyChangedEvent(this)

// Display results
println "Conversion completed:"
println "  Source: ${sourceClass}"
println "  Target: ${targetClass}"
println "  Converted: ${convertedCount} annotations"

Dialogs.showInfoNotification(
    "Conversion Complete",
    "Successfully converted ${convertedCount} annotations from '${sourceClass}' to '${targetClass}'"
)