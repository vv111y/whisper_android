#ifndef _TFLITEENGINE_H_
#define _TFLITEENGINE_H_

#include <string>
#include <vector>

class TFLiteEngine {
public:
    TFLiteEngine() {};
    ~TFLiteEngine() {};

    int loadModel(const char *modelPath, const bool isMultilingual);
    void freeModel();

    std::string transcribeBuffer(std::vector<float> samples);
    std::string transcribeFile(const char* waveFile);

    // Returns the last human-readable error string (empty on success)
    const char* getLastError() const { return lastError.c_str(); }
    // Attempts to load a model into a temporary interpreter and then disposes it, leaving
    // the global engine untouched. Returns 0 on success, else negative error code.
    int validateModel(const char* modelPath, const bool isMultilingual);

private:
    // Add any private members or helper functions as needed
    std::string lastError;
    void setError(const std::string& err) { lastError = err; }
};

#endif // _TFLITEENGINE_H_

