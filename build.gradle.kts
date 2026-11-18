// 根构建文件：声明 Android Gradle Plugin，不在根应用
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
