/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.streaming.compress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.cassandra.io.compress.CompressionMetadata;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.ChannelProxy;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.streaming.ProgressInfo;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.streaming.StreamWriter;
import org.apache.cassandra.utils.Pair;

/**
 * StreamWriter for compressed SSTable.
 */
public class CompressedStreamWriter extends StreamWriter
{
    public static final int CHUNK_SIZE = 10 * 1024 * 1024;

    private final CompressionInfo compressionInfo;

    public CompressedStreamWriter(SSTableReader sstable, Collection<Pair<Long, Long>> sections, CompressionInfo compressionInfo, StreamSession session)
    {
        super(sstable, sections, session);
        this.compressionInfo = compressionInfo;
    }

    @Override
    public void write(DataOutputStreamPlus out) throws IOException
    {
        long totalSize = totalSize();
        try (ChannelProxy fc = sstable.getDataChannel().sharedCopy())
        {
            long progress = 0L;
            // calculate chunks to transfer. we want to send continuous chunks altogether.
            List<Pair<Long, Long>> sections = getTransferSections(compressionInfo.chunks);
            // stream each of the required sections of the file
            for (final Pair<Long, Long> section : sections)
            {
                // length of the section to stream
                long length = section.right - section.left;
                // tracks write progress
                long bytesTransferred = 0;
                while (bytesTransferred < length)
                {
                    final long bytesTransferredFinal = bytesTransferred;
                    final int toTransfer = (int) Math.min(CHUNK_SIZE, length - bytesTransferred);
                    limiter.acquire(toTransfer);
                    //把fc中的数据写到out，
                    //因为用了闭包，所以用bytesTransferredFinal(好比是内部类，所以必须是final类型的变量)
                    //(这句话是错的，在Eclipse中去掉final，也没有提升错误)
                    long lastWrite = out.applyToChannel((wbc) -> fc.transferTo(section.left + bytesTransferredFinal, toTransfer, wbc));
                    bytesTransferred += lastWrite;
                    progress += lastWrite;
                    session.progress(sstable.descriptor, ProgressInfo.Direction.OUT, progress, totalSize);
                }
            }
        }
    }

    @Override
    protected long totalSize()
    {
        long size = 0;
        // calculate total length of transferring chunks
        for (CompressionMetadata.Chunk chunk : compressionInfo.chunks)
            size += chunk.length + 4; // 4 bytes for CRC
        return size;
    }

    // chunks are assumed to be sorted by offset
    private List<Pair<Long, Long>> getTransferSections(CompressionMetadata.Chunk[] chunks)
    {
        List<Pair<Long, Long>> transferSections = new ArrayList<>();
        Pair<Long, Long> lastSection = null;
        for (CompressionMetadata.Chunk chunk : chunks)
        {
            if (lastSection != null)
            {
                if (chunk.offset == lastSection.right) //前后两个chunk是相连的，合并成一个
                {
                    // extend previous section to end of this chunk
                    lastSection = Pair.create(lastSection.left, chunk.offset + chunk.length + 4); // 4 bytes for CRC
                }
                else
                {
                    transferSections.add(lastSection);
                    lastSection = Pair.create(chunk.offset, chunk.offset + chunk.length + 4);
                }
            }
            else
            {
                //生成一个包含开始和结束位置的Pair，
                //注意：这里的位置都是压缩文件中的原有位置，并不是代表解压后的数据位置
                lastSection = Pair.create(chunk.offset, chunk.offset + chunk.length + 4);
            }
        }
        if (lastSection != null)
            transferSections.add(lastSection);
        return transferSections;
    }
}
