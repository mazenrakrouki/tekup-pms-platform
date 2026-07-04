package com.pms.user.service;

import com.pms.user.dto.ResourceRequest;
import com.pms.user.dto.ResourceResponse;
import com.pms.user.entity.Resource;
import com.pms.user.mapper.ResourceMapper;
import com.pms.user.repository.ResourceRepository;
import com.pms.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.pms.shared.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final ResourceMapper resourceMapper;

    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public List<ResourceResponse> findAll() {
        return resourceMapper.toResponseList(resourceRepository.findAllActive());
    }

    @PreAuthorize("hasAuthority('VIEW_RESOURCES')")
    @Transactional(readOnly = true)
    public ResourceResponse findById(Long id) {
        return resourceMapper.toResponse(loadResource(id));
    }

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public ResourceResponse create(ResourceRequest request) {
        var user = userRepository.findById(request.userId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable : " + request.userId()));

        if (resourceRepository.findActiveByUserId(request.userId()).isPresent()) {
            throw new IllegalArgumentException("Cet utilisateur est déjà une ressource active");
        }

        Resource resource = Resource.builder()
                .user(user)
                .dailyRate(request.dailyRate())
                .tccRate(request.tccRate())
                .staffingStart(request.staffingStart())
                .staffingEnd(request.staffingEnd())
                .build();

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public ResourceResponse update(Long id, ResourceRequest request) {
        Resource resource = loadResource(id);

        resource.setDailyRate(request.dailyRate());
        resource.setTccRate(request.tccRate());
        resource.setStaffingStart(request.staffingStart());
        resource.setStaffingEnd(request.staffingEnd());

        return resourceMapper.toResponse(resourceRepository.save(resource));
    }

    @PreAuthorize("hasAuthority('MANAGE_RESOURCES')")
    @Transactional
    public void delete(Long id) {
        Resource resource = loadResource(id);
        resource.setDeleted(true);
        resourceRepository.save(resource);
    }

    private Resource loadResource(Long id) {
        return resourceRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new NotFoundException("Ressource introuvable : " + id));
    }
}
