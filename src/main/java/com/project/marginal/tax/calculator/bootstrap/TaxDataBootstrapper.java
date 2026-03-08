package com.project.marginal.tax.calculator.bootstrap;

import com.project.marginal.tax.calculator.repository.TaxRateRepository;
import com.project.marginal.tax.calculator.service.TaxDataImportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class TaxDataBootstrapper implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TaxDataBootstrapper.class);

    private final S3Client s3Client;
    private final TaxDataImportService importer;
    private final TaxRateRepository taxRateRepo;

    @Value("${tax.s3-bucket:}")
    private String s3Bucket;

    @Value("${tax.s3-key:}")
    private String s3Key;

    @Override
    public void run(ApplicationArguments args) throws Exception {

        if (taxRateRepo.count() > 0) {
            log.info("Tax data already present, skipping bootstrap");
            return;
        }
        if (s3Bucket.isBlank() || s3Key.isBlank()) {
            log.warn("S3 not configured, skipping bootstrap");
            return;
        }
        log.info("DB empty, bootstrapping from s3://{}/{}", s3Bucket, s3Key);
        try (InputStream in = s3Client.getObject(
                GetObjectRequest.builder().bucket(s3Bucket).key(s3Key).build())) {
            importer.importData(in);
        }
    }
}
