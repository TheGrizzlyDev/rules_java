// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.java.testrunner.extension;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes(
    "com.google.devtools.build.java.testrunner.extension.PersistentTestRunnerExtension")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public final class ExtensionAnnotationProcessor extends AbstractProcessor {

  private static final String EXTENSION_INTERFACE =
      "com.google.devtools.build.java.testrunner.extension.Extension";
  private static final String OUTPUT_RESOURCE =
      "META-INF/persistent-test-runner-extensions.txt";

  private final TreeSet<String> extensionClasses = new TreeSet<>();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    TypeElement extensionInterface =
        processing().getElementUtils().getTypeElement(EXTENSION_INTERFACE);
    TypeMirror extensionInterfaceType =
        extensionInterface != null ? extensionInterface.asType() : null;

    for (TypeElement annotation : annotations) {
      for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
        if (!(annotated instanceof TypeElement)) {
          continue;
        }
        TypeElement type = (TypeElement) annotated;
        if (extensionInterfaceType != null
            && !processing().getTypeUtils().isAssignable(type.asType(), extensionInterfaceType)) {
          processing()
              .getMessager()
              .printMessage(
                  Diagnostic.Kind.ERROR,
                  "@PersistentTestRunnerExtension classes must implement "
                      + EXTENSION_INTERFACE,
                  type);
          continue;
        }
        extensionClasses.add(type.getQualifiedName().toString());
      }
    }

    if (roundEnv.processingOver() && !extensionClasses.isEmpty()) {
      try {
        FileObject file =
            processing()
                .getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", OUTPUT_RESOURCE);
        try (Writer writer = file.openWriter()) {
          for (String name : extensionClasses) {
            writer.write(name);
            writer.write('\n');
          }
        }
      } catch (IOException e) {
        processing()
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR,
                "failed to write " + OUTPUT_RESOURCE + ": " + e.getMessage());
      }
    }

    return true;
  }

  private javax.annotation.processing.ProcessingEnvironment processing() {
    return processingEnv;
  }
}
