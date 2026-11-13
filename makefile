# ตั้งค่าตัวแปรพื้นฐาน
args   ?=
mode   ?= linux
JAVA   ?= java
JAVAC  ?= javac
JF     ?=
JCF    ?=

# กำหนดตัวแบ่ง classpath ตาม OS (Linux ใช้ ':', Windows ใช้ ';')
ifeq ($(mode),windows)
    CP_SEP := ;
else
    CP_SEP := :
endif

# กำหนด Classpath รวม
CP := ./bin$(CP_SEP)./libs$(CP_SEP)./src

# ค้นหาไฟล์ Java ทั้งหมดอัตโนมัติ
SRC_FILES := $(wildcard ./src/*.java)

.PHONY: build run

build:
ifeq ($(filter $(mode),linux windows),)
	@echo "Mode not found"
else
	$(JAVAC) -d ./bin -cp "$(CP)" $(SRC_FILES)
	@echo "Build successful ($(mode))!"
endif

run:
ifeq ($(filter $(mode),linux windows),)
	@echo "Mode not found"
else
	$(JAVA) -cp "$(CP)" Main $(args)
endif