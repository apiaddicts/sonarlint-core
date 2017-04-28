/*
 * SonarLint Core - Implementation
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.plugin;

import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;

public class RemotePluginTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void shouldEqual() {
    RemotePlugin clirr1 = new RemotePlugin("clirr");
    RemotePlugin clirr2 = new RemotePlugin("clirr");
    RemotePlugin checkstyle = new RemotePlugin("checkstyle");
    assertThat(clirr1.equals(clirr2), is(true));
    assertThat(clirr1.equals(clirr1), is(true));
    assertThat(clirr1.equals(checkstyle), is(false));
  }

  @Test
  public void shouldMarshal() {
    RemotePlugin clirr = new RemotePlugin("clirr").setFile("clirr-1.1.jar", "fakemd5");
    String text = clirr.marshal();
    assertThat(text, is("clirr,clirr-1.1.jar|fakemd5"));
  }

  @Test
  public void shouldCreateRemotePlugin() throws IOException {
    File f = temp.newFile();
    FileUtils.write(f, "test");
    PluginInfo info = new PluginInfo("key");
    info.setJarFile(f);
    RemotePlugin plugin = RemotePlugin.create(info);
    assertThat(plugin.getKey(), is("key"));
  }

  @Test
  public void shouldUnmarshal() {
    RemotePlugin clirr = RemotePlugin.unmarshal("clirr,clirr-1.1.jar|fakemd5");
    assertThat(clirr.getKey(), is("clirr"));
    assertThat(clirr.file().getFilename(), is("clirr-1.1.jar"));
    assertThat(clirr.file().getHash(), is("fakemd5"));
  }
}
