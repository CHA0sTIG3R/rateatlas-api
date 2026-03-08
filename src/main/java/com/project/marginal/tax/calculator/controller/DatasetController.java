package com.project.marginal.tax.calculator.controller;

import com.project.marginal.tax.calculator.dto.DatasetFreshnessResponse;
import com.project.marginal.tax.calculator.service.DatasetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    @GetMapping("/latest")
    public ResponseEntity<DatasetFreshnessResponse> getLatestDataset() {
        return ResponseEntity.ok(datasetService.getLatestDataset());
    }
}