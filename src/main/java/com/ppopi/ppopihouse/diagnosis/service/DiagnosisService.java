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
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 반려동물입니다. petId=" + petId));

        if (!pet.getMember().getMemberId().equals(memberId)) {
            throw new SecurityException("해당 반려동물에 대한 접근 권한이 없습니다. memberId=" + memberId);
        }

        // 🌟 [프로브 센서 1] 이미지 유효성 검사 서버(8081) 연동 예외 포착 레이어
        ImageValidationResponse validation;
        try {
            validation = imageValidationClient.validate(image);
        } catch (Exception e) {
            throw new RuntimeException("PROBE_ERROR [Validation API]: 이미지 검증 마이크로서비스(8081) 호출 실패. 서버가 꺼져있거나 호스트 바인딩이 잘못되었습니다. 원인=" + e.getMessage());
        }

        if (validation == null || !validation.isValid()) {
            throw new IllegalArgumentException(
                    validation != null ? "PROBE_ERROR [Validation API]: 이미지 검증 엔진 거부 -> " + validation.getMessage() : "이미지 유효성 검사에 실패했습니다."
            );
        }

        // 🌟 [프로브 센서 2] Cloudinary 스토리지 업로드 연동 예외 포착 레이어
        String imageUrl;
        try {
            imageUrl = imageStorageService.upload(image);
        } catch (Exception e) {
            throw new RuntimeException("PROBE_ERROR [Cloudinary Storage]: 이미지 자산 업로드 실패. 운영 인프라의 환경변수(Cloud Name, API Key)가 유실되었을 수 있습니다. 원인=" + e.getMessage());
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

        // 🌟 [프로브 센서 3] AI 서버(ngrok) 통신 예외 포착 레이어
        AiDiagnosisResponse aiResponse;
        try {
            aiResponse = aiDiagnosisClient.diagnose(aiRequest);
        } catch (Exception e) {
            throw new RuntimeException("PROBE_ERROR [AI Engine API]: AI 진단 서버 호출 중 네트워크 예외가 발생했습니다. ngrok 주소 유효성 및 우회 헤더 설정을 확인하세요. 원인=" + e.getMessage());
        }

        String diseaseName = normalizeDiseaseName(aiResponse.getDisease());
        String species = normalizeSpecies(pet.getSpecies());
        String affectedArea = normalizeAffectedArea(aiResponse.getFamilyLabel());

        // 🌟 [프로브 센서 4] ★가장 유력★ 운영 데이터베이스 질병 마스터 데이터 부재 검증 레이어
        EyeDiseaseCode disease = eyeDiseaseCodeRepository
                .findByDiseaseNameAndInputSpeciesAnd
