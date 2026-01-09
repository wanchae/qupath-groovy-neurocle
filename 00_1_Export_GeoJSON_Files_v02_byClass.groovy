// https://forum.image.sc/t/batch-saving-and-loading-annotations-from-masks-with-names/43313
// Script by Melvin : Export geojson file

// Input name of your annotation (geojson) folder
export_folder = "Geojson_test4"
export_extension = ".geojson"

// List of specified classes to export (e.g., "Tumor", "Stroma")
// Leave empty or undefined to export all classes
def specifiedClasses = []  // Define your classes here, or leave it empty to export all

// Check if specifiedClasses is not defined or empty, set to export all annotations
if (!specifiedClasses || specifiedClasses.isEmpty()) {
    specifiedClasses = getAnnotationObjects()
        .findAll { it.getPathClass() != null }
        .collect { it.getPathClass().getName() }
        .unique()  // Ensure no duplicates
}

// Get annotations and filter by specified classes
def annotations = getAnnotationObjects()
    .findAll { it.getPathClass() != null && specifiedClasses.contains(it.getPathClass().getName()) }
    .collect { new qupath.lib.objects.PathAnnotationObject(it.getROI(), it.getPathClass()) }

// Get Gson instance
def gson = GsonTools.getInstance(true)

// Create 'annotations' directory if it doesn't exist
def path = buildFilePath(PROJECT_BASE_DIR, export_folder)
def filename = GeneralTools.stripExtension(getProjectEntry().getImageName())
new File(path).mkdir()

// Write to a new file inside the 'annotations' directory
File file = new File(path + "/" + filename + export_extension)
file.write(gson.toJson(annotations))

print 'Done!'
