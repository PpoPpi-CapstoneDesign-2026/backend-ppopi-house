package com.ppopi.ppopihouse.diagnosis.service;

import com.ppopi.ppopihouse.diagnosis.domain.Diagnosis;
import com.ppopi.ppopihouse.diagnosis.domain.EyeDiseaseCode;
import com.ppopi.ppopihouse.diagnosis.domain.EyeSymptom;
import com.ppopi.ppopihouse.diagnosis.dto.external.AiDiagnosisRequest;
import com.ppopi.ppopihouse.diagnosis.dto.external.AiDiagnosisResponse;
import com.ppopi.ppopihouse.diagnosis.dto.external.ImageValidationResponse;
import com.ppopi.ppopihouse.diagnosis.dto.response.DiagnosisResponse;
import com.ppopi.ppopihouse.diagnosis.dto.response.RecentDiagnosisResponse;
import com.ppopi.ppopihouse.diagnosis.repository.DiagnosisRepository;
import com.ppopi.ppopihouse.diagnosis.repository.EyeDiseaseCodeRepository;
import com.ppopi.ppopihouse.global.infra.cloud.ImageStorageService;
import com.ppopi.ppopihouse.pet.domain.Pet;
import com.ppopi.ppopihouse.pet.repository.PetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private final PetRepository petRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final EyeDiseaseCodeRepository eyeDiseaseCodeRepository;
    private final ImageValidationClient imageValidationClient;
    private final ImageStorageService imageStorageService;
    private final AiDiagnosisClient aiDiagnosisClient;

    public DiagnosisResponse diagnose(
            Long memberId, 
            Long petId,
            MultipartFile image,
            List<Long> symptomIds
    ) {
        // 1. 반려동물 검증 레이어 (원본 보존)
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new IllegalArgumentException("실패 [회원/펫 스코프]: 존재하지 않는 반려동물입니다. petId=" + petId));

        if (!pet.getMember().getMemberId().equals(memberId)) {
            throw new SecurityException("실패 [보안 인가]: 해당 반려동물에 대한 접근 권한이 없습니다. memberId=" + memberId);
        }

        // 2. 이미지 유효성 검사 모듈 (원본 보존 및 에러 컨텍스트 강화)
        ImageValidationResponse validation;
        try {
            validation = imageValidationClient.validate(image);
        } catch (Exception e) {
            throw new IllegalArgumentException("실패 [외부 연동 - Validation API]: 이미지 검증 서버(8081) 통신 불가. 원인=" + e.getMessage());
        }

        if (validation == null || !validation.isValid()) {
            throw new IllegalArgumentException(
                    validation != null ? "실패 [외부 연동 - Validation API 엔진 거부]: " + validation.getMessage() : "이미지 유효성 검사에 실패했습니다."
            );
        }

        // 3. Cloudinary 이미지 업로드 마스터 (원본 보존 및 에러 컨텍스트 강화)
        String imageUrl;
        try {
            imageUrl = imageStorageService.upload(image);
        } catch (Exception e) {
            throw new IllegalArgumentException("실패 [외부 인프라 - Cloudinary]: 이미지 업로드 실패. 환경변수 점검 필요. 원인=" + e.getMessage());
        }

        List<String> symptoms = symptomIds == null
                ? List.of()
                : symptomIds.stream()
                .map(EyeSymptom::fromId)
                .map(EyeSymptom::getDescription)
                .toList();

        AiDiagnosisRequest aiRequest = new AiDiagnosisRequest(
                imageUrl,
                pet.getSpecies(),
                pet.getBreed(),
                calculateAge(pet.getBirthYear()),
                pet.getSex(),
                symptoms
        );

        // 4. AI 진단 엔진 통신 (원본 보존 및 에러 컨텍스트 강화)
        AiDiagnosisResponse aiResponse;
        try {
            aiResponse = aiDiagnosisClient.diagnose(aiRequest);
        } catch (Exception e) {
            throw new IllegalArgumentException("실패 [외부 연동 - AI Engine]: ngrok 서버 통신 실패. 터널링 상태 확인 필요. 원인=" + e.getMessage());
        }

        String diseaseName = normalizeDiseaseName(aiResponse.getDisease());
        String species = normalizeSpecies(pet.getSpecies());
        String affectedArea = normalizeAffectedArea(aiResponse.getFamilyLabel());

        // 5. DB 질병 코드 마스터 조회 (★가장 유력한 폭사 포인트 최적화)
        // 글로벌 예외 처리기가 500으로 가로채지 못하도록 명시적 비즈니스 익셉션 문구 결합
        EyeDiseaseCode disease = eyeDiseaseCodeRepository
                .findByDiseaseNameAndInputSpeciesAndAffectedArea(diseaseName, species, affectedArea)
                .orElseThrow(() -> new IllegalArgumentException(
                        "실패 [데이터 무결성 - 운영 DB]: eye_disease_code 테이블에 매핑 데이터가 없습니다! 요구조건 -> [질병명=" + diseaseName
                                + ", 종=" + species
                                + ", 부위=" + affectedArea + "]"
                ));

        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setPet(pet);
        diagnosis.setDiagnosisDate(LocalDate.now(ZoneId.of("Asia/Seoul")));
        diagnosis.setImageUrl(imageUrl);
        diagnosis.setDisease(disease);

        diagnosis.setSymptomIds(
                symptomIds == null ? "" :
                        symptomIds.stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(","))
        );

        diagnosis.setTriageKey(aiResponse.getTriage());
        diagnosis.setTriageConfidence(aiResponse.getTriageConfidence());

        diagnosis.setGuideMsg(aiResponse.getGuidanceMessage());
        diagnosis.setGuideAction(aiResponse.getGuidanceAction());
        diagnosis.setGuideWarn(aiResponse.getGuidanceWarning());

        // 6. DB 영속화 레이어 (원본 보존)
        if (aiResponse.getTriageConfidence() >= 0.4f) {
            try {
                diagnosisRepository.save(diagnosis);
            } catch (Exception e) {
                throw new IllegalArgumentException("실패 [영속성 계층 - DB Save]: 진단 결과 테이블 데이터 저장 실패. 제약조건 위반 의심. 원인=" + e.getMessage());
            }
        }

        return new DiagnosisResponse(
                imageUrl,
                formatStatus(aiResponse.getTriage()),
                diseaseName,
                formatAffectedArea(aiResponse.getFamilyLabel()),
                formatConfidence(aiResponse.getTriageConfidence()),
                aiResponse.getGuidanceAction(),
                aiResponse.getGuidanceMessage(),
                aiResponse.getGuidanceWarning()
        );
    }

    // 하단 유틸리티 및 조회 메서드 명세 원본과 100% 동일하게 유지
    public RecentDiagnosisResponse getTodayDiagnosis(Long memberId, Long petId, LocalDate date) {
        Pet pet = petRepository.findById(petId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 반려동물입니다."));
        if (!pet.getMember().getMemberId().equals(memberId)) { throw new SecurityException("해당 반려동물에 대한 접근 권한이 없습니다."); }
        return diagnosisRepository.findTopByPet_PetIdAndDiagnosisDateOrderByDiagnosisIdDesc(petId, date).map(d -> { List<Long> checkedIds = parseSymptomIds(d.getSymptomIds()); List<RecentDiagnosisResponse.SymptomChecklist> symptoms = buildSymptomChecklist(checkedIds); return toRecentDiagnosisResponse(d, symptoms); }).orElseGet(() -> RecentDiagnosisResponse.empty(buildSymptomChecklist(List.of())));
    }
    private int calculateAge(int birthYear) { return Year.now().getValue() - birthYear; }
    private String formatAffectedArea(String area) { if (area == null) return null; return switch (area) { case "cornea_ulcerative", "cornea_nonulcerative" -> "각막"; case "conjunctiva" -> "결막"; case "eyelid" -> "눈꺼풀"; case "lens_vitreous" -> "수정체/유리체"; case "tear" -> "눈물"; case "normal" -> "정상"; default -> area; }; }
    private String formatStatus(String triage) { if (triage == null || triage.isBlank()) return "UNKNOWN"; return triage.trim().toUpperCase(); }
    private String normalizeDiseaseName(String diseaseName) { if (diseaseName == null || diseaseName.isBlank()) { throw new IllegalArgumentException("AI 질병명이 비어 있습니다."); } return switch (diseaseName.toLowerCase()) { case "normal" -> "정상"; default -> diseaseName.trim(); }; }
    private String normalizeSpecies(String species) { if (species == null || species.isBlank()) { throw new IllegalArgumentException("반려동물 종 정보가 비어 있습니다."); } return switch (species.toLowerCase()) { case "dog", "강아지", "개" -> "DOG"; case "cat", "고양이" -> "CAT"; default -> species.toUpperCase(); }; }
    private int formatConfidence(double confidence) { return (int) Math.round(confidence * 100); }
    private RecentDiagnosisResponse toRecentDiagnosisResponse(Diagnosis d, List<RecentDiagnosisResponse.SymptomChecklist> symptoms) { return new RecentDiagnosisResponse(true, d.getImageUrl(), formatStatus(d.getTriageKey()), d.getDisease().getDiseaseName(), formatAffectedArea(d.getDisease().getAffectedArea()), formatConfidence(d.getTriageConfidence()), d.getGuideAction(), d.getGuideMsg(), d.getGuideWarn(), symptoms); }
    private List<RecentDiagnosisResponse.SymptomChecklist> buildSymptomChecklist(List<Long> checkedIds) { return Arrays.stream(EyeSymptom.values()).filter(symptom -> checkedIds.contains(symptom.getId())).map(symptom -> new RecentDiagnosisResponse.SymptomChecklist(
            symptom.getId(), symptom.getDescription())).toList(); }
    private List<Long> parseSymptomIds(String symptomIds) { if (symptomIds == null || symptomIds.isBlank()) { return List.of(); } return Arrays.stream(symptomIds.split(",")).map(String::trim).filter(value -> !value.isBlank()).map(Long::valueOf).toList(); }
    private String normalizeAffectedArea(String affectedArea) { if (affectedArea == null || affectedArea.isBlank()) { throw new IllegalArgumentException("AI affectedArea 값이 비어 있습니다."); } return affectedArea.trim(); }
}
