package com.ccdev.drainage.web;

import com.ccdev.drainage.repo.AssetRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetRepository assetRepo;

    public AssetController(AssetRepository assetRepo) {
        this.assetRepo = assetRepo;
    }

    @GetMapping("/versions")
    public List<?> versions() {
        return assetRepo.findAllVersions();
    }

    @GetMapping("/versions/{id}/segments")
    public Map<String, Object> segments(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("version", assetRepo.findVersionById(id));
        result.put("segments", assetRepo.findSegmentsByVersion(id));
        result.put("manholes", assetRepo.findManholesByVersion(id));
        return result;
    }

    @GetMapping("/segments/{id}")
    public Map<String, Object> segmentDetail(@PathVariable Long id) {
        var seg = assetRepo.findSegmentById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("segment", seg);
        result.put("version", assetRepo.findVersionById(seg.versionId()));
        result.put("mappingsFrom", assetRepo.findMappingsFromOldSegment(id));
        return result;
    }

    @GetMapping("/mappings")
    public List<?> mappings() {
        return assetRepo.findAllMappings();
    }
}
