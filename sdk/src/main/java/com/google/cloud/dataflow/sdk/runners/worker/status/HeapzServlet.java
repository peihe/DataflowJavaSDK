/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.worker.status;

import com.google.cloud.dataflow.sdk.util.MemoryMonitor;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Respond to /heapz with a page allowing downloading of the heap dumps.
 *
 * <p>Respond to /heapz?action=download with a download of the actual heap dump.
 */
public class HeapzServlet extends BaseStatusServlet {

  public HeapzServlet() {
    super("heapz");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String action = req.getParameter("action");
    if (action == null || action.isEmpty()) {
      resp.setContentType("text/html;charset=utf-8");
      resp.setStatus(HttpServletResponse.SC_OK);

      ServletOutputStream writer = resp.getOutputStream();
      writer.println("<html>");
      writer.println(String.format(
          "Click <a href=\"%s\">here to download heap dump</a>", getPath("action=download")));
      writer.println("</html>");
      return;
    } else if ("download".equals(action)) {
      doDownload(resp);
    }
  }

  private void doDownload(HttpServletResponse resp) throws IOException {
    File file;

    try {
      file = MemoryMonitor.dumpHeap();
    } catch (MalformedObjectNameException
        | InstanceNotFoundException | ReflectionException | MBeanException e) {
      resp.setContentType("text/html;charset=utf-8");
      resp.setStatus(HttpServletResponse.SC_OK);

      ServletOutputStream writer = resp.getOutputStream();
      writer.println("<html>\nFailed to dump heap: <br>\n<pre>");
      writer.println(e.toString());
      writer.println("</pre>\n</html>");
      return;
    }

    resp.setContentType("application/octet-stream");
    resp.setContentLength((int) file.length());
    resp.setHeader(
        "Content-Disposition", String.format("attachment; filename=\"%s\"", file.getName()));

    try {
      Files.copy(file, resp.getOutputStream());
      resp.setStatus(HttpServletResponse.SC_OK);
    } catch (IOException e) {
      resp.reset();
      resp.setContentType("text/html;charset=utf-8");
      ServletOutputStream writer = resp.getOutputStream();
      writer.println("<html>\nFailed to dump heap: <br>\n<pre>\n");
      writer.println(e.toString());
      writer.println("</pre>\n</html>");
      resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }
}
