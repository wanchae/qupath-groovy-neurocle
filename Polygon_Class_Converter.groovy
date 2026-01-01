import qupath.lib.roi.GeometryTools
import qupath.lib.objects.PathObjects
import qupath.lib.common.GeneralTools

// 1. 변환하고 싶은 타겟 클래스 이름 설정 (예: ADE)
def targetClassName = "Tumor" 
def targetRectangleROI = "Tissue" 

// 현재 선택된 객체 (우리가 그린 Red Polygon 영역)
def selector = getSelectedObject()
def hierarchy = getCurrentHierarchy()

if (selector == null) {
    print("오류: 변환할 영역(Polygon)을 먼저 선택해주세요.")
    return
}

// 2. 타겟 클래스 및 Selector 기하 정보 가져오기
def targetClass = getPathClass(targetClassName)
def selectorGeom = selector.getROI().getGeometry()
def plane = selector.getROI().getImagePlane()

// 변경 사항을 저장할 리스트
def objectsToAdd = []
def objectsToRemove = []

// 3. Selector와 겹치는 모든 기존 Annotation 찾기 (Selector 자신은 제외)
def overlapping = hierarchy.getAnnotationObjects().findAll { existing ->
    if (existing == selector) return false
    // ROI 겹침 1차 확인 (속도 최적화)
    if (!existing.getROI().intersects(selector.getROI().getBoundsX(), selector.getROI().getBoundsY(), selector.getROI().getBoundsWidth(), selector.getROI().getBoundsHeight())) return false
    // Geometry 정밀 교차 확인
    return existing.getROI().getGeometry().intersects(selectorGeom)
}

overlapping.each { existing ->
    def existingGeom = existing.getROI().getGeometry()
    
    try {
        // [A] 교집합 계산 (영역 안쪽) -> 클래스 변경 대상
        def insideGeom = existingGeom.intersection(selectorGeom)
        
        // [B] 차집합 계산 (영역 바깥쪽) -> 기존 클래스 유지 대상
        def outsideGeom = existingGeom.difference(selectorGeom)

        // 4. 안쪽 영역 처리 (새로운 클래스 적용)
        if (!insideGeom.isEmpty()) {
            // 안쪽 영역만큼 새로운 Annotation 생성
            def newROI = GeometryTools.geometryToROI(insideGeom, plane)
            def newAnnotation = PathObjects.createAnnotationObject(newROI, targetClass)
            objectsToAdd << newAnnotation
        }

        // 5. 바깥쪽 영역 처리 (기존 Annotation 업데이트)
        if (outsideGeom.isEmpty()) {
            // 기존 영역이 Selector 안에 완전히 포함된 경우 -> 기존 객체 삭제
            objectsToRemove << existing
        } else {
            // 일부만 포함된 경우 -> 기존 객체의 모양을 바깥쪽 영역으로 축소
            // (setROI는 즉시 반영되므로 별도 리스트 없이 여기서 수행)
            def remainingROI = GeometryTools.geometryToROI(outsideGeom, plane)
            existing.setROI(remainingROI)
        }
        
    } catch (Exception e) {
        print("Geometry 연산 오류 (ID: ${existing.getID()}): ${e.getMessage()}")
    }
}

// 6. 변경 사항 적용
// 새로 만든(변환된) 조각들 추가
addObjects(objectsToAdd)

// 완전히 포함되어 필요 없어진 기존 객체들 삭제
removeObjects(objectsToRemove, true)

// *선택사항*: 영역 지정용으로 썼던 Red Polygon(Selector)은 이제 필요 없으므로 삭제
// 만약 남겨두고 싶다면 아래 줄을 주석 처리하세요.
removeObject(selector, true)

// UI 업데이트
fireHierarchyUpdate()
print("완료: 선택 영역 내부의 객체들을 ${targetClassName}로 변환했습니다.")

selectObjectsByClassification(targetRectangleROI);
runPlugin('qupath.lib.plugins.objects.FillAnnotationHolesPlugin', '{}')

//selectObjectsByClassification(targetClassName);
//mergeSelectedAnnotations()