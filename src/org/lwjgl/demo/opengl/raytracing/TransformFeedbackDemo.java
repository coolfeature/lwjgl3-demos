/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.opengl.raytracing;

import org.lwjgl.BufferUtils;
import org.lwjgl.demo.opengl.util.DemoUtils;
import org.lwjgl.demo.opengl.util.WavefrontMeshLoader;
import org.lwjgl.demo.opengl.util.WavefrontMeshLoader.Mesh;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.opengl.NVDrawTexture;
import org.lwjgl.system.libffi.Closure;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static java.lang.Math.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MathUtil.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This demo uses transform feedback to first store the view-space positions and normals of the vertices in a buffer object.
 * <p>
 * Afterwards, the triangles in this buffer are intersected with eye rays in a simple compute shader.
 * <p>
 * This demo differs from all other raytracing demos in that the scene SSBO for the compute shader is generated "dynamically"
 * via transform feedback. This allows for dynamic scenes with possible model transformations.
 * 
 * @author Kai Burjack
 */
public class TransformFeedbackDemo {

    long window;
    int width = 1024;
    int height = 768;
    boolean resetFramebuffer = true;

    int raytraceTexture;
    int fullScreenVao;
    int computeProgram;
    int quadProgram;
    int feedbackProgram;
    int vaoScene;
    int ssbo;
    int sampler;

    int modelMatrixUniform;
    int viewMatrixUniform;
    int normalMatrixUniform;
    int projectionMatrixUniform;

    int ray00Uniform;
    int ray10Uniform;
    int ray01Uniform;
    int ray11Uniform;
    int trianglesSsboBinding;
    int framebufferImageBinding;
    int workGroupSizeX;
    int workGroupSizeY;

    Mesh mesh;
    float mouseDownX;
    float mouseX;
    boolean mouseDown;

    float currRotationAboutY = 0.0f;
    float rotationAboutY = 0.8f;
    float elapsedTime = 0.0f;
    long lastTime;

    float cameraRadius = 5.0f;
    Matrix4f modelMatrix = new Matrix4f();
    Matrix4f projMatrix = new Matrix4f();
    Matrix4f viewMatrix = new Matrix4f();
    Matrix4f modelViewMatrix = new Matrix4f();
    Matrix3f normalMatrix = new Matrix3f();
    Matrix4f invProjMatrix = new Matrix4f();
    Vector3f tmpVector = new Vector3f();
    Vector3f cameraPosition = new Vector3f();
    Vector3f cameraLookAt = new Vector3f(0.0f, 0.0f, 0.0f);
    Vector3f cameraUp = new Vector3f(0.0f, 1.0f, 0.0f);
    ByteBuffer matrixByteBuffer = BufferUtils.createByteBuffer(4 * 16);

    GLFWErrorCallback errCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;
    GLFWCursorPosCallback cpCallback;
    GLFWMouseButtonCallback mbCallback;

    Closure debugProc;
    GLCapabilities caps;

    static {
        /*
         * Tell LWJGL that we only want 4.3 functionality.
         */
        System.setProperty("org.lwjgl.opengl.maxVersion", "4.3");
    }

    void init() throws IOException {
        glfwSetErrorCallback(errCallback = new GLFWErrorCallback() {
            GLFWErrorCallback delegate = GLFWErrorCallback.createPrint(System.err);

            @Override
            public void invoke(int error, long description) {
                if (error == GLFW_VERSION_UNAVAILABLE)
                    System.err.println("This demo requires OpenGL 4.3 or higher.");
                delegate.invoke(error, description);
            }

            @Override
            public void release() {
                delegate.release();
                super.release();
            }
        });

        if (glfwInit() != GL_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);

        window = glfwCreateWindow(width, height, "Raytracing Demo (transform feedback)", NULL, NULL);
        if (window == NULL) {
            throw new AssertionError("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (action != GLFW_RELEASE)
                    return;

                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, GL_TRUE);
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                if (width > 0 && height > 0
                        && (TransformFeedbackDemo.this.width != width || TransformFeedbackDemo.this.height != height)) {
                    TransformFeedbackDemo.this.width = width;
                    TransformFeedbackDemo.this.height = height;
                    TransformFeedbackDemo.this.resetFramebuffer = true;
                }
            }
        });

        glfwSetCursorPosCallback(window, cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                TransformFeedbackDemo.this.mouseX = (float) x;
            }
        });

        glfwSetMouseButtonCallback(window, mbCallback = new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    TransformFeedbackDemo.this.mouseDownX = TransformFeedbackDemo.this.mouseX;
                    TransformFeedbackDemo.this.mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    TransformFeedbackDemo.this.mouseDown = false;
                    TransformFeedbackDemo.this.rotationAboutY = TransformFeedbackDemo.this.currRotationAboutY;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.getWidth() - width) / 2, (vidmode.getHeight() - height) / 2);
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);

        IntBuffer framebufferSize = BufferUtils.createIntBuffer(2);
        nglfwGetFramebufferSize(window, memAddress(framebufferSize), memAddress(framebufferSize) + 4);
        width = framebufferSize.get(0);
        height = framebufferSize.get(1);

        /* Load OBJ model */
        WavefrontMeshLoader loader = new WavefrontMeshLoader();
        mesh = loader.loadMesh("org/lwjgl/demo/opengl/models/smoothicosphere.obj.zip");

        caps = GL.createCapabilities();
        debugProc = GLUtil.setupDebugMessageCallback();

        /* Create all needed GL resources */
        createRaytracingTexture();
        createSampler();
        createSceneSSBO();
        createSceneVao();
        createFeedbackProgram();
        createComputeProgram();
        initComputeProgram();
        if (!caps.GL_NV_draw_texture) {
            createFullScreenVao();
            createQuadProgram();
        }

        lastTime = System.nanoTime();
    }

    /**
     * Create a Shader Storage Buffer Object into which Transform Feedback will write the view-space position and
     * normals of the scene.
     */
    void createSceneSSBO() {
        this.ssbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, ssbo);
        glBufferData(GL_ARRAY_BUFFER, 4 * (4 + 4) * mesh.numVertices, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Creates a VAO with a full-screen quad VBO.
     */
    void createFullScreenVao() {
        this.fullScreenVao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(fullScreenVao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        ByteBuffer bb = BufferUtils.createByteBuffer(4 * 2 * 6);
        FloatBuffer fv = bb.asFloatBuffer();
        fv.put(-1.0f).put(-1.0f);
        fv.put(1.0f).put(-1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(1.0f).put(1.0f);
        fv.put(-1.0f).put(1.0f);
        fv.put(-1.0f).put(-1.0f);
        glBufferData(GL_ARRAY_BUFFER, bb, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Creates a VAO for the scene.
     */
    void createSceneVao() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        long bufferSize = 4 * (3 + 3) * mesh.numVertices;
        long normalsOffset = 4L * 3 * mesh.numVertices;
        glBufferData(GL_ARRAY_BUFFER, bufferSize, GL_STATIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, mesh.positions);
        glBufferSubData(GL_ARRAY_BUFFER, normalsOffset, mesh.normals);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, normalsOffset);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        this.vaoScene = vao;
    }

    /**
     * Create the full-scren quad shader.
     *
     * @throws IOException
     */
    void createQuadProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad.vs", GL_VERTEX_SHADER, "330");
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/quad.fs", GL_FRAGMENT_SHADER, "330");
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertex");
        glBindFragDataLocation(program, 0, "color");
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.quadProgram = program;

        glUseProgram(quadProgram);
        int texUniform = glGetUniformLocation(quadProgram, "tex");
        glUniform1i(texUniform, 0);
        glUseProgram(0);
    }

    /**
     * Create the raster shader.
     *
     * @throws IOException
     */
    void createFeedbackProgram() throws IOException {
        int program = glCreateProgram();
        int vshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/rasterFeedback.vs", GL_VERTEX_SHADER);
        int fshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/rasterFeedback.fs", GL_FRAGMENT_SHADER);
        glAttachShader(program, vshader);
        glAttachShader(program, fshader);
        glBindAttribLocation(program, 0, "vertexPosition");
        glBindAttribLocation(program, 1, "vertexNormal");
        glTransformFeedbackVaryings(program, new String[] { "viewPosition", "viewNormal" }, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.feedbackProgram = program;

        glUseProgram(feedbackProgram);
        modelMatrixUniform = glGetUniformLocation(feedbackProgram, "modelMatrix");
        viewMatrixUniform = glGetUniformLocation(feedbackProgram, "viewMatrix");
        projectionMatrixUniform = glGetUniformLocation(feedbackProgram, "projectionMatrix");
        normalMatrixUniform = glGetUniformLocation(feedbackProgram, "normalMatrix");
        glUseProgram(0);
    }

    /**
     * Create the tracing compute shader program.
     *
     * @throws IOException
     */
    void createComputeProgram() throws IOException {
        int program = glCreateProgram();
        int cshader = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/feedbackSsboTriangle.glsl",
                GL_COMPUTE_SHADER);
        int random = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/random.glsl", GL_COMPUTE_SHADER);
        int randomCommon = DemoUtils.createShader("org/lwjgl/demo/opengl/raytracing/randomCommon.glsl",
                GL_COMPUTE_SHADER, "330");
        glAttachShader(program, cshader);
        glAttachShader(program, random);
        glAttachShader(program, randomCommon);
        glLinkProgram(program);
        int linked = glGetProgrami(program, GL_LINK_STATUS);
        String programLog = glGetProgramInfoLog(program);
        if (programLog.trim().length() > 0) {
            System.err.println(programLog);
        }
        if (linked == 0) {
            throw new AssertionError("Could not link program");
        }
        this.computeProgram = program;
    }

    /**
     * Initialize the compute shader.
     */
    void initComputeProgram() {
        glUseProgram(computeProgram);
        ray00Uniform = glGetUniformLocation(computeProgram, "ray00");
        ray10Uniform = glGetUniformLocation(computeProgram, "ray10");
        ray01Uniform = glGetUniformLocation(computeProgram, "ray01");
        ray11Uniform = glGetUniformLocation(computeProgram, "ray11");
        IntBuffer workGroupSize = BufferUtils.createIntBuffer(3);
        glGetProgramiv(computeProgram, GL_COMPUTE_WORK_GROUP_SIZE, workGroupSize);
        workGroupSizeX = workGroupSize.get(0);
        workGroupSizeY = workGroupSize.get(1);

        /* Query the binding point of the SSBO */
        /*
         * First, obtain the "resource index" used for further queries on that resource.
         */
        int boxesResourceIndex = glGetProgramResourceIndex(computeProgram, GL_SHADER_STORAGE_BLOCK, "Triangles");
        IntBuffer props = BufferUtils.createIntBuffer(1);
        IntBuffer params = BufferUtils.createIntBuffer(1);
        props.put(0, GL_BUFFER_BINDING);
        /* Now query the "BUFFER_BINDING" of that resource */
        glGetProgramResourceiv(computeProgram, GL_SHADER_STORAGE_BLOCK, boxesResourceIndex, props, null, params);
        trianglesSsboBinding = params.get(0);

        /* Query the "image binding point" of the framebuffer image */
        int loc = glGetUniformLocation(computeProgram, "framebufferImage");
        glGetUniformiv(computeProgram, loc, params);
        framebufferImageBinding = params.get(0);

        glUseProgram(0);
    }

    /**
     * Create the texture that will serve as our framebuffer for the ray tracer.
     */
    void createRaytracingTexture() {
        this.raytraceTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, raytraceTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Create the sampler to sample the framebuffer texture within the shader.
     */
    void createSampler() {
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    void resizeFramebufferTexture() {
        glDeleteTextures(raytraceTexture);
        createRaytracingTexture();
    }

    void update() {
        if (mouseDown) {
            /*
             * If mouse is down, compute the camera rotation based on mouse cursor location.
             */
            currRotationAboutY = rotationAboutY + (mouseX - mouseDownX) * 0.01f;
        } else {
            currRotationAboutY = rotationAboutY;
        }

        /* Rotate camera about Y axis. */
        cameraPosition.set((float) sin(-currRotationAboutY) * cameraRadius, 0.0f,
                (float) cos(-currRotationAboutY) * cameraRadius);
        projMatrix.setPerspective((float) Math.toRadians(30.0f), (float) width / height, 0.01f, 100.0f);
        projMatrix.invert(invProjMatrix);
        viewMatrix.setLookAt(cameraPosition, cameraLookAt, cameraUp);

        /* Perform model transformation */
        long thisTime = System.nanoTime();
        float delta = (thisTime - lastTime) / 1E9f;
        modelMatrix.setRotationXYZ(delta * 0.2f, delta, delta * 0.3f);

        /* Compute normal matrix */
        viewMatrix.mul(modelMatrix, modelViewMatrix).normal(normalMatrix);

        if (resetFramebuffer) {
            projMatrix.setPerspective((float) Math.toRadians(60.0f), (float) width / height, 0.01f, 100.0f);
            resizeFramebufferTexture();
            resetFramebuffer = false;
        }
    }

    /**
     * Transform the vertices and store them in a buffer object via transform feedback.
     */
    void transform() {
        glUseProgram(feedbackProgram);

        /* Update matrices in shader */
        glUniformMatrix4fv(modelMatrixUniform, 1, false, modelMatrix.get(matrixByteBuffer));
        glUniformMatrix4fv(viewMatrixUniform, 1, false, viewMatrix.get(matrixByteBuffer));
        glUniformMatrix4fv(projectionMatrixUniform, 1, false, projMatrix.get(matrixByteBuffer));
        glUniformMatrix3fv(normalMatrixUniform, 1, false, normalMatrix.get(matrixByteBuffer));

        /* Transform the vertices and store them in a buffer object */
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, this.ssbo);
        glBeginTransformFeedback(GL_TRIANGLES);
        glBindVertexArray(vaoScene);
        glDrawArrays(GL_TRIANGLES, 0, mesh.numVertices);
        glBindVertexArray(0);
        glEndTransformFeedback();
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        glUseProgram(0);
    }

    /**
     * Compute one frame by tracing the scene using our compute shader.
     */
    void trace() {
        glUseProgram(computeProgram);

        /* Set viewing frustum corner rays in shader */
        invProjMatrix.transformProject(tmpVector.set(-1, -1, 0));
        glUniform3f(ray00Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invProjMatrix.transformProject(tmpVector.set(-1, 1, 0));
        glUniform3f(ray01Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invProjMatrix.transformProject(tmpVector.set(1, -1, 0));
        glUniform3f(ray10Uniform, tmpVector.x, tmpVector.y, tmpVector.z);
        invProjMatrix.transformProject(tmpVector.set(1, 1, 0));
        glUniform3f(ray11Uniform, tmpVector.x, tmpVector.y, tmpVector.z);

        /* Bind the SSBO containing our feedback triangles */
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, ssbo);
        /* Bind level 0 of framebuffer texture as writable image in the shader. */
        glBindImageTexture(framebufferImageBinding, raytraceTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);

        /* Compute appropriate invocation dimension. */
        int worksizeX = mathRoundPoT(width);
        int worksizeY = mathRoundPoT(height);

        /* Invoke the compute shader. */
        glDispatchCompute(worksizeX / workGroupSizeX, worksizeY / workGroupSizeY, 1);

        /*
         * Synchronize all writes to the framebuffer image before we let OpenGL source texels from it afterwards when
         * rendering the final image with the full-screen quad.
         */
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        /* Reset bindings. */
        glBindImageTexture(framebufferImageBinding, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, trianglesSsboBinding, 0);
        glUseProgram(0);
    }

    /**
     * Present the final image on the screen/viewport.
     */
    void present() {
        if (caps.GL_NV_draw_texture) {
            /*
             * Use some fancy NV extension to draw a screen-aligned textured quad without needing a VAO/VBO or a shader.
             */
            NVDrawTexture.glDrawTextureNV(raytraceTexture, sampler, 0.0f, 0.0f, width, height, 0.0f, 0.0f, 0.0f, 1.0f,
                    1.0f);
        } else {
            /*
             * Draw the rendered image on the screen using textured full-screen quad.
             */
            glUseProgram(quadProgram);
            glBindVertexArray(fullScreenVao);
            glBindTexture(GL_TEXTURE_2D, raytraceTexture);
            glBindSampler(0, this.sampler);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    void loop() {
        while (glfwWindowShouldClose(window) == GL_FALSE) {
            glfwPollEvents();
            glViewport(0, 0, width, height);

            update();
            transform();
            trace();
            present();

            glfwSwapBuffers(window);
        }
    }

    void run() {
        try {
            init();
            loop();

            if (debugProc != null)
                debugProc.release();

            errCallback.release();
            keyCallback.release();
            fbCallback.release();
            cpCallback.release();
            mbCallback.release();
            glfwDestroyWindow(window);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            glfwTerminate();
        }
    }

    public static void main(String[] args) {
        new TransformFeedbackDemo().run();
    }

}