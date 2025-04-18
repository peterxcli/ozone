/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

/**
 * Test input stream parsing with signatures.
 */
public class TestSignedChunksInputStream {

  @Test
  public void emptyfile() throws IOException {
    InputStream is = fileContent("0;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40");
    String result = IOUtils.toString(is, UTF_8);
    assertEquals("", result);

    is = fileContent("0;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40\r"
        + "\n");
    result = IOUtils.toString(is, UTF_8);
    assertEquals("", result);
  }

  @Test
  public void singlechunk() throws IOException {
    //test simple read()
    InputStream is = fileContent("0A;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40\r"
        + "\n1234567890\r\n");
    String result = IOUtils.toString(is, UTF_8);
    assertEquals("1234567890", result);

    //test read(byte[],int,int)
    is = fileContent("0A;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40\r"
        + "\n1234567890\r\n");
    byte[] bytes = new byte[10];
    IOUtils.read(is, bytes, 0, 10);
    assertEquals("1234567890",
        new String(bytes, UTF_8));
  }

  @Test
  public void singlechunkwithoutend() throws IOException {
    //test simple read()
    InputStream is = fileContent("0A;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40\r"
        + "\n1234567890");
    String result = IOUtils.toString(is, UTF_8);
    assertEquals("1234567890", result);

    //test read(byte[],int,int)
    is = fileContent("0A;chunk-signature"
        +
        "=23abb2bd920ddeeaac78a63ed808bc59fa6e7d3ef0e356474b82cdc2f8c93c40\r"
        + "\n1234567890");
    byte[] bytes = new byte[10];
    IOUtils.read(is, bytes, 0, 10);
    assertEquals("1234567890",
        new String(bytes, UTF_8));
  }

  @Test
  public void multichunks() throws IOException {
    //test simple read()
    InputStream is = fileContent("0a;chunk-signature=signature\r\n"
        + "1234567890\r\n"
        + "05;chunk-signature=signature\r\n"
        + "abcde\r\n");
    String result = IOUtils.toString(is, UTF_8);
    assertEquals("1234567890abcde", result);

    //test read(byte[],int,int)
    is = fileContent("0a;chunk-signature=signature\r\n"
        + "1234567890\r\n"
        + "05;chunk-signature=signature\r\n"
        + "abcde\r\n");
    byte[] bytes = new byte[15];
    IOUtils.read(is, bytes, 0, 15);
    assertEquals("1234567890abcde",
        new String(bytes, UTF_8));
  }

  private InputStream fileContent(String content) {
    return new SignedChunksInputStream(
        new ByteArrayInputStream(content.getBytes(UTF_8)));
  }
}
