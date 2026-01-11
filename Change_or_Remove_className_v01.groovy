// QuPath Groovy Script - Annotation Name 변경/삭제
// 이 스크립트는 선택된 또는 모든 Annotation의 Name을 변경하거나 삭제합니다.

import qupath.lib.objects.PathAnnotationObject
import qupath.lib.gui.dialogs.Dialogs

// =============================================
// 옵션 1: 선택된 Annotation의 Name 삭제
// =============================================
def deleteSelectedAnnotationName() {
    def selectedObject = getSelectedObject()
    
    if (selectedObject instanceof PathAnnotationObject) {
        selectedObject.setName(null)  // Name을 null로 설정하여 삭제
        fireHierarchyUpdate()
        println "선택된 Annotation의 Name이 삭제되었습니다."
    } else {
        println "Annotation이 선택되지 않았습니다."
    }
}

// =============================================
// 옵션 2: 선택된 Annotation의 Name을 특정 값으로 변경
// =============================================
def changeSelectedAnnotationName(String newName) {
    def selectedObject = getSelectedObject()
    
    if (selectedObject instanceof PathAnnotationObject) {
        selectedObject.setName(newName)
        fireHierarchyUpdate()
        println "선택된 Annotation의 Name이 '${newName}'으로 변경되었습니다."
    } else {
        println "Annotation이 선택되지 않았습니다."
    }
}

// =============================================
// 옵션 3: 모든 Annotation의 Name 삭제
// =============================================
def deleteAllAnnotationNames() {
    def annotations = getAnnotationObjects()
    
    if (annotations.isEmpty()) {
        println "Annotation이 없습니다."
        return
    }
    
    annotations.each { annotation ->
        annotation.setName(null)
    }
    fireHierarchyUpdate()
    println "${annotations.size()}개의 Annotation Name이 삭제되었습니다."
}

// =============================================
// 옵션 4: 모든 Annotation의 Name을 특정 값으로 변경
// =============================================
def changeAllAnnotationNames(String newName) {
    def annotations = getAnnotationObjects()
    
    if (annotations.isEmpty()) {
        println "Annotation이 없습니다."
        return
    }
    
    annotations.each { annotation ->
        annotation.setName(newName)
    }
    fireHierarchyUpdate()
    println "${annotations.size()}개의 Annotation Name이 '${newName}'으로 변경되었습니다."
}

// =============================================
// 옵션 5: 특정 Name을 가진 Annotation만 변경
// =============================================
def changeSpecificAnnotationNames(String oldName, String newName) {
    def annotations = getAnnotationObjects()
    def count = 0
    
    annotations.each { annotation ->
        if (annotation.getName() == oldName) {
            annotation.setName(newName)
            count++
        }
    }
    
    fireHierarchyUpdate()
    println "${count}개의 '${oldName}' Annotation이 '${newName}'으로 변경되었습니다."
}

// =============================================
// 옵션 6: 대화형 Name 변경 (사용자 입력 받기)
// =============================================
def interactiveChangeAnnotationName() {
    def selectedObject = getSelectedObject()
    
    if (!(selectedObject instanceof PathAnnotationObject)) {
        Dialogs.showWarningNotification("경고", "Annotation을 먼저 선택하세요.")
        return
    }
    
    def currentName = selectedObject.getName() ?: "(없음)"
    def newName = Dialogs.showInputDialog(
        "Annotation Name 변경", 
        "새로운 Name을 입력하세요.\n현재 Name: ${currentName}", 
        currentName == "(없음)" ? "" : currentName
    )
    
    if (newName != null) {
        if (newName.trim().isEmpty()) {
            selectedObject.setName(null)
            println "Annotation Name이 삭제되었습니다."
        } else {
            selectedObject.setName(newName)
            println "Annotation Name이 '${newName}'으로 변경되었습니다."
        }
        fireHierarchyUpdate()
    }
}

// =============================================
// 옵션 7: Name에 prefix 또는 suffix 추가
// =============================================
def addPrefixToAnnotationNames(String prefix) {
    def annotations = getAnnotationObjects()
    
    annotations.each { annotation ->
        def currentName = annotation.getName()
        if (currentName != null && !currentName.isEmpty()) {
            annotation.setName(prefix + currentName)
        }
    }
    fireHierarchyUpdate()
    println "모든 Annotation Name에 prefix '${prefix}'가 추가되었습니다."
}

// =============================================
// 실행할 기능 선택 (원하는 줄의 주석을 제거하여 실행)
// =============================================

// 1. 선택된 Annotation의 Name 삭제
// deleteSelectedAnnotationName()

// 2. 선택된 Annotation의 Name을 "새로운이름"으로 변경
// changeSelectedAnnotationName("새로운이름")

// 3. 모든 Annotation의 Name 삭제
deleteAllAnnotationNames()

// 4. 모든 Annotation의 Name을 "통일된이름"으로 변경
// changeAllAnnotationNames("통일된이름")

// 5. "Stroma_adjusted_adjusted"를 "Stroma"로 변경
// changeSpecificAnnotationNames("Stroma_adjusted_adjusted", "Stroma")

// 6. 대화창을 통해 선택된 Annotation의 Name 변경 (추천)
//interactiveChangeAnnotationName()

// 7. 모든 Annotation Name에 prefix 추가
// addPrefixToAnnotationNames("Region_")