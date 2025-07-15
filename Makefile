ifeq ($(VERILATOR_ROOT),)
VERILATOR = verilator
else
export VERILATOR_ROOT
VERILATOR = $(VERILATOR_ROOT)/bin/verilator
endif

EMB = 0

#  ifeq ($(EMB),1)
CHISEL_HOME := $(CURDIR)
#  endif

CHISEL_SRC = $(wildcard $(CHISEL_HOME)/src/main/scala/**/*.scala)
SOURCE=$(wildcard vsrc/top_*.v)                                                                           
SOURCE_C=$(wildcard csrc/main.cpp)                                                                           
WAVE = --trace-fst
SOURCE_NAME=$(notdir $(patsubst %.v,%,$(SOURCE)))
SOURCE_SIM=$(wildcard csrc/sim_main.cpp)                                                                           
SOURCE_FST=$(wildcard csrc/sim_fst_main.cpp)                                                                           

IROM = $(CHISEL_HOME)/src/test/main.c
IROM_HOME = $(CHISEL_HOME)/src/test/

CFLAGS = -cc --exe --build -j 0 -Wall

DEFINE_TOP = --top-module 
MODULE = $(notdir $(wildcard $(CHISEL_HOME)/*.v))
TOP_MODULE = $(notdir $(patsubst %.v,%,$(wildcard $(CHISEL_HOME)/*.v)))
TOP_NAME = SIM_CPU

ifeq ($(TOP),0)
	DEFINE_TOP = 
endif

TARGET = obj_dir/V$(SOURCE_NAME)
VERILOG_SRC = $(shell find $(abspath ./vsrc) -name "top_*.v")

all: $(TARGET) $(VERILOG_SRC)
	@echo "-- Verilator $(TOP_MODULE)"
	@echo "-- VERILATE & BUILD --------"
irom: $(IROM)
	cd $(IROM_HOME) && make
$(TARGET):$(SOURCE) $(VERILOG_SRC) 
	$(VERILATOR) $(CFLAGS) $(DEFINE_TOP) $(TOP_NAME) $(SOURCE) $(SOURCE_SIM)
	@echo "-- RUN ---------------------"
	@echo '\n'
	obj_dir/VSIM_CPU
	@echo ""
	@echo "-- DONE --------------------"
#  	$(VERILATOR) $(CFLAGS) $(DEFINE_TOP) $(TOP_MODULE) $(SOURCE) $(SOURCE_SIM)
#  	obj_dir/V$(TOP_MODULE)

$(VERILOG_SRC):$(CHISEL_SRC) $(IROM)
	cd $(CHISEL_HOME) && sbt run
	echo "// verilator lint_off UNUSEDSIGNAL" > ./vsrc/top_module.v
	echo "/* verilator lint_off DECLFILENAME */" >> ./vsrc/top_module.v
	echo "/* verilator lint_off SYNCASYNCNET*/" >> ./vsrc/top_module.v
	cat ./$(MODULE) >> ./vsrc/top_module.v
	cd $(CHISEL_HOME)

fst: $(VERILOG_SRC)
	$(VERILATOR) $(CFLAGS) $(DEFINE_TOP) $(TOP_MODULE) $(SOURCE) $(WAVE) $(SOURCE_FST) -Wno-VARHIDDEN
	@echo "-- RUN ---------------------"
	@echo ""
	@echo '\n'
	obj_dir/V$(TOP_NAME)
	@surfer waves.fst -s TEST &
	@echo "-- DONE --------------------"

#  	@gtkwave waves.fst ./TEST.gtkw &
.PHONY: default all clean run

clean mostlyclean distclean maintainer-clean::
	-rm -rf obj_dir *.log *.dmp *.vpd *.fst core $(BUILD_DIR) *.fir *.svg
	-find . -maxdepth 1 -name '*.json' ! -name 'compile_commands.json' -delete

