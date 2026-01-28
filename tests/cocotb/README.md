# cocotb tests for MyNoC

Prerequisites:
- Activate your cocotb conda env: `conda activate cocotb-env`
- Install a simulator, e.g. Verilator: `brew install verilator`

Run the smoke test:

```bash
cd tests/cocotb
make SIM=verilator
```

If `cocotb-config` is not found, ensure the `cocotb-env` is activated and cocotb is installed in that environment.
