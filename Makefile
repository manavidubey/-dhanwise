.PHONY: web backend test package run clean

JAVA_HOME ?= /opt/homebrew/Cellar/openjdk/23.0.2/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

web:
	cd web && npm install && npm run build
	rm -rf src/main/resources/static
	mkdir -p src/main/resources/static
	cp -R web/dist/. src/main/resources/static/

backend:
	mvn -B package

test:
	mvn -B test

package: test web backend

run: web
	mvn -B spring-boot:run

clean:
	mvn -B clean
	rm -rf web/dist src/main/resources/static
