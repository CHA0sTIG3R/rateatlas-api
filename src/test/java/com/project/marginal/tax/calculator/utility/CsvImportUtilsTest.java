package com.project.marginal.tax.calculator.utility;

import com.project.marginal.tax.calculator.dto.BracketEntry;
import com.project.marginal.tax.calculator.entity.FilingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CsvImportUtilsTest {

    private CsvImportUtils csvUtil;
    private S3Client s3Client;
    private final String HEADER;

    public CsvImportUtilsTest() {
        HEADER = "c0,c1,c2,c3,c4,c5,c6,c7,c8,c9,c10,c11,c12\n";
    }

    @BeforeEach
    public void setUp() {
        csvUtil = new CsvImportUtils();
        s3Client = S3Client.builder().build();
    }

    @Test
    public void missingColumns_shouldThrow() {
        String csv = "c0,c1,c2\n"                     // only 3 cols
                + "2023,10%,$0\n";                // data also 3 cols
        var in = new ByteArrayInputStream(csv.getBytes());
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> csvUtil.importFromStream(in));
    }

    @Test
    public void extraColumns_areIgnored() throws Exception {
        String hdr = HEADER.replaceFirst("c12", "c12,c13,c14");
        String row = "2023,10%,$0,$5000,12%,$0,$6000,14%,$0,$7000,16%,$0,$8000,foo,bar\n";

        List<BracketEntry> entries = csvUtil.importFromStream(
                new ByteArrayInputStream((hdr + row).getBytes())
        );
        assertEquals(4, entries.size());

        BracketEntry mfj = entries.stream()
                .filter(e -> e.getStatus() == FilingStatus.MFJ)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("0"), mfj.getRangeStart());
        assertEquals(new BigDecimal("5000"), mfj.getRangeEnd());
    }

    @Test
    public void blankYearRows_areSkipped() throws Exception {
        String blankRow = ",,,,,,,,,,,,,\n";  // 13 empties
        String validRow = "2023,10%,$0,$100,12%,$0,$200,14%,$0,$300,16%,$0,$400\n";

        List<BracketEntry> entries = csvUtil.importFromStream(
                new ByteArrayInputStream((HEADER + blankRow + validRow).getBytes())
        );
        assertEquals(4, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.getYear() == 2023));
    }

    @Test
    public void normalRows_parseCorrectly() throws Exception {
        String row1 = "2022,10%,$0,$1000,12%,$0,$2000,14%,$0,$3000,16%,$0,$4000\n";
        String row2 = "2022,18%,$1000,$2000,20%,$2000,$3000,22%,$3000,$4000,24%,$4000,$5000\n";
        List<BracketEntry> entries = csvUtil.importFromStream(
                new ByteArrayInputStream((HEADER + row1 + row2).getBytes())
        );
        assertEquals(8, entries.size());

        List<BracketEntry> sBrackets = entries.stream()
                .filter(e -> e.getStatus() == FilingStatus.S)
                .sorted(Comparator.comparing(BracketEntry::getRangeStart))
                .toList();
        assertEquals(BigDecimal.ZERO,   sBrackets.get(0).getRangeStart());
        assertEquals(new BigDecimal("3000"), sBrackets.get(0).getRangeEnd());
        assertEquals(new BigDecimal("3000"), sBrackets.get(1).getRangeStart());
        assertEquals(new BigDecimal("4000"), sBrackets.get(1).getRangeEnd());
    }

    @Test
    public void noIncomeTaxBranch_setsZeroAndNull() throws Exception {
        // MFJ with "No income tax" should yield rate=0, start=0, end=null
        String row = "2025,No income tax,$123,$456,12%,$0,$100,14%,$0,$200,16%,$0,$300\n";
        List<BracketEntry> entries = csvUtil.importFromStream(
                new ByteArrayInputStream((HEADER + row).getBytes())
        );

        BracketEntry mfj = entries.stream()
                .filter(e -> e.getStatus() == FilingStatus.MFJ)
                .findFirst().orElseThrow();

        assertEquals(2025, mfj.getYear());
        assertEquals(0f, mfj.getRate());
        assertEquals(BigDecimal.ZERO, mfj.getRangeStart());
        assertNull(mfj.getRangeEnd());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
    public void importPerformance() throws Exception {
        InputStream in = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket("marginal-tax-rate-calculator-hamza")
                        .key("irs/irs_historical.csv")
                        .build());
        long start = System.nanoTime();
        csvUtil.importFromStream(in);
        long elapsedSec = (System.nanoTime() - start)/1_000_000_000;
        assertTrue(elapsedSec < 10, "Import took too long: " + elapsedSec + "s");
    }

}
