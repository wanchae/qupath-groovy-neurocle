// https://forum.image.sc/t/batch-saving-and-loading-annotations-from-masks-with-names/43313/2
// Script by Melvin from Batch saving and loading annotations from masks with names 2020.10.



import qupath.lib.io.GsonTools

// Input name of your annotation (geojson) folder
json_folder = "Geojson_Thyroid_Tumor_NeuroT6_v01_240920"
//export_focus .... etc
json_extension = '.geojson'

// Instantiate tools
def gson = GsonTools.getInstance(true);

// Get path of image
def filename = GeneralTools.stripExtension(getProjectEntry().getImageName())

// Prepare template
def type = new com.google.gson.reflect.TypeToken<List<qupath.lib.objects.PathObject>>() {}.getType();
def json = new File(buildFilePath(PROJECT_BASE_DIR, json_folder, filename + json_extension))

// Deserialize
deserializedAnnotations = gson.fromJson(json.getText('UTF-8'), type);

// Add to image
addObjects(deserializedAnnotations);

// Resolve hierarchy
resolveHierarchy()

print "Done!"