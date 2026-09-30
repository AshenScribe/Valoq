/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package database;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BucketUtils {

    private BucketUtils() {}

    public static String toTimeBucket(Instant instant) {
        ZonedDateTime dateTime = instant.atZone(ZoneOffset.UTC);

        return String.format("%04d-%02d", dateTime.getYear(), dateTime.getMonthValue());
    }

    public static int bucket(UUID uuid) {
        return bucket(uuid, 10);
    }

    public static int bucket(UUID uuid, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be greater than 0");
        }

        return (uuid.hashCode() & Integer.MAX_VALUE) % bucketCount;
    }

    public static List<String> generateTimeBuckets(Instant start, Instant end) {

        List<String> buckets = new ArrayList<>();

        ZonedDateTime current =
                start.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);

        ZonedDateTime endZoned = end.atZone(ZoneOffset.UTC);

        while (current.isBefore(endZoned) || current.getMonth() == endZoned.getMonth()) {
            buckets.add(toTimeBucket(current.toInstant()));
            current = current.plusMonths(1);
        }

        return buckets;
    }
}
